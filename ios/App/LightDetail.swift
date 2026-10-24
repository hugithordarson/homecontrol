import SwiftUI

/// One light's controls: on/off, brightness, warmth and color
struct LightDetail: View {
    @Environment(House.self) private var house
    let lightID: String

    var body: some View {
        if let light = house.light(lightID) {
            NavigationStack {
                Form {
                    Section {
                        Toggle("On", isOn: Binding(get: { light.on }, set: { _ in house.toggle(light) }))
                            .disabled(!light.reachable)

                        if light.dimmable && light.on {
                            LevelSlider(level: light.level ?? 100) { house.setLevel(light, $0) }
                        }
                    }

                    if light.on && (light.supportsTemperature || light.supportsColor) {
                        Section("Warmth") {
                            TemperatureSlider(light: light) { house.setTemperature([light], $0) }
                        }
                    }

                    if light.on {
                        Section("Palette") {
                            PaletteGrid(presets: house.state.palette.filter { light.supportsColor || $0.kelvin != nil }) {
                                house.apply($0, to: [light])
                            }
                        }
                    }
                }
                .navigationTitle(light.name)
                .navigationBarTitleDisplayMode(.inline)
            }
        }
    }
}

struct TemperatureSlider: View {
    let light: Light
    let commit: (Int) -> Void
    @State private var value: Double?

    var body: some View {
        HStack {
            Circle().fill(kelvinColor(light.warmest)).frame(width: 14)
            Slider(value: Binding(get: { value ?? Double(light.kelvin ?? light.warmest) }, set: { value = $0 }), in: Double(light.warmest)...Double(light.coldest), step: 50) { editing in
                if !editing, let value {
                    commit(Int(value))
                    self.value = nil
                }
            }
            Circle().fill(kelvinColor(light.coldest)).frame(width: 14)
        }
    }
}

struct PaletteGrid: View {
    let presets: [Preset]
    let apply: (Preset) -> Void

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 64))], spacing: 12) {
            ForEach(presets) { preset in
                Button { apply(preset) } label: {
                    VStack(spacing: 4) {
                        Swatch(colors: preset.colors)
                            .frame(width: 40, height: 40)
                        Text(preset.name)
                            .font(.caption2)
                            .lineLimit(2)
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.primary)
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 6)
    }
}

/// A round chip: one color, or a set of colors as pie slices
struct Swatch: View {
    let colors: [Color]

    var body: some View {
        ZStack {
            ForEach(colors.indices, id: \.self) { index in
                Circle()
                    .trim(from: CGFloat(index) / CGFloat(colors.count), to: CGFloat(index + 1) / CGFloat(colors.count))
                    .stroke(colors[index], lineWidth: 20)
                    .padding(10)
            }
        }
        .rotationEffect(.degrees(-90))
        .overlay(Circle().stroke(Color(.separator)))
    }
}
