import SwiftUI

/// The dashboard: scenes on top, then every room with its lights
struct HouseView: View {
    @Environment(House.self) private var house
    @State private var selectedLightID: String?
    @State private var showingSettings = false

    var body: some View {
        NavigationStack {
            List {
                if let error = house.error {
                    Section {
                        Label(error, systemImage: "wifi.exclamationmark")
                            .foregroundStyle(.red)
                    }
                }

                ForEach(house.state.warnings, id: \.self) { warning in
                    Label(warning, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                        .font(.footnote)
                }

                if house.state.runningFades > 0 {
                    Section {
                        HStack {
                            Label("A scheduled fade is running", systemImage: "clock.arrow.circlepath")
                            Spacer()
                            Button("Stop") { house.stopFades() }
                        }
                    }
                }

                if !house.state.scenes.isEmpty {
                    Section("Scenes") {
                        ScenesRow()
                    }
                }

                ForEach(house.state.rooms) { room in
                    RoomSection(room: room, multipleHubs: Set(house.state.rooms.compactMap(\.hub)).count > 1) { light in
                        selectedLightID = light.id
                    }
                }
            }
            .overlay {
                if !house.loaded && house.error == nil {
                    ProgressView()
                }
            }
            .refreshable {
                await house.refresh(force: true)
            }
            .navigationTitle("Ljós")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Menu {
                        Button("All on", systemImage: "lightbulb.fill") { house.setAll(on: true) }
                        Button("All off", systemImage: "lightbulb.slash") { house.setAll(on: false) }
                        Divider()
                        Button("Settings", systemImage: "gear") { showingSettings = true }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .sheet(item: Binding(get: { selectedLightID.flatMap(house.light) }, set: { selectedLightID = $0?.id })) { light in
                LightDetail(lightID: light.id)
                    .presentationDetents([.medium, .large])
            }
            .sheet(isPresented: $showingSettings) {
                SettingsView()
            }
        }
    }
}

struct ScenesRow: View {
    @Environment(House.self) private var house

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                ForEach(house.state.scenes) { scene in
                    Button(scene.name) { house.applyScene(scene) }
                        .buttonStyle(.bordered)
                        .disabled(scene.away)
                }
            }
            .padding(.vertical, 4)
        }
        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
    }
}

struct RoomSection: View {
    @Environment(House.self) private var house
    let room: Room
    let multipleHubs: Bool
    let select: (Light) -> Void

    var body: some View {
        Section {
            if room.anyOn && room.lights.contains(where: \.dimmable) {
                LevelSlider(level: roomLevel) { house.setRoomLevel(room, $0) }
            }

            ForEach(room.lights) { light in
                LightRow(light: light)
                    .contentShape(Rectangle())
                    .onTapGesture { select(light) }
            }
        } header: {
            HStack {
                Text(multipleHubs && room.hub != nil ? "\(room.name) · \(room.hub!)" : room.name)
                Spacer()
                Toggle(room.name, isOn: Binding(get: { room.anyOn }, set: { house.setRoom(room, on: $0) }))
                    .labelsHidden()
                    .controlSize(.mini)
            }
            .contextMenu {
                ForEach(house.state.palette) { preset in
                    Button(preset.name) { house.apply(preset, to: room.lights.filter(\.reachable)) }
                }
            }
        }
    }

    /// The average level of the room's lit, dimmable lights
    private var roomLevel: Int {
        let levels = room.lights.filter { $0.on && $0.dimmable }.compactMap(\.level)
        return levels.isEmpty ? 100 : levels.reduce(0, +) / levels.count
    }
}

struct LightRow: View {
    @Environment(House.self) private var house
    let light: Light

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: light.on ? "lightbulb.fill" : "lightbulb")
                .font(.title3)
                .foregroundStyle(light.displayColor)
                .frame(width: 28)

            VStack(alignment: .leading) {
                Text(light.name)
                Text(meta)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Toggle(light.name, isOn: Binding(get: { light.on }, set: { _ in house.toggle(light) }))
                .labelsHidden()
                .disabled(!light.reachable)
        }
        .opacity(light.reachable ? 1 : 0.5)
    }

    private var meta: String {
        if !light.reachable {
            return "unreachable"
        }

        if !light.on {
            return "off"
        }

        var parts: [String] = []

        if light.dimmable, let level = light.level {
            parts.append("\(level)%")
        }

        if light.colorMode {
            parts.append("color")
        }
        else if let kelvin = light.kelvin, light.supportsTemperature {
            parts.append("\(kelvin) K")
        }

        return parts.joined(separator: " · ")
    }
}

/// A brightness slider that only sends when the finger lifts — a command landing
/// mid-transition makes the bulb snap, so no stream of updates while dragging
struct LevelSlider: View {
    let level: Int
    let commit: (Int) -> Void
    @State private var value: Double?

    var body: some View {
        HStack {
            Image(systemName: "sun.min")
            Slider(value: Binding(get: { value ?? Double(level) }, set: { value = $0 }), in: 1...100) { editing in
                if !editing, let value {
                    commit(Int(value.rounded()))
                    self.value = nil
                }
            }
            Image(systemName: "sun.max")
        }
        .foregroundStyle(.secondary)
    }
}
