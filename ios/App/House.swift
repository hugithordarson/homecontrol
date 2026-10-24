import Foundation
import Observation

/// The app's view of the house: the last /api/state, refreshed by polling while the
/// app is in front. Commands update the state optimistically and hold off polling
/// briefly, so a poll that lands before the hub has caught up doesn't flick the UI back.

@MainActor
@Observable
final class House {
    var state = HouseState.empty
    var loaded = false
    var error: String?

    private var holdPollingUntil = Date.distantPast
    private var lastScenes: [SceneInfo] = []

    func refresh(force: Bool = false) async {
        if !force && Date() < holdPollingUntil {
            return
        }

        do {
            let fresh = try await API.current.state()

            if force || Date() >= holdPollingUntil {
                state = fresh
            }

            loaded = true
            error = nil

            if fresh.scenes != lastScenes {
                lastScenes = fresh.scenes
                LjosShortcuts.updateAppShortcutParameters()
            }
        }
        catch {
            self.error = error.localizedDescription
        }
    }

    /// Runs while the app is active
    func poll() async {
        while !Task.isCancelled {
            await refresh()
            try? await Task.sleep(for: .seconds(3))
        }
    }

    // MARK: Commands

    func toggle(_ light: Light) {
        update(light.id) { $0.on.toggle() }
        send { try await $0.toggle(light.id) }
    }

    func setRoom(_ room: Room, on: Bool) {
        updateRoom(room.id) { $0.on = on }
        send { try await $0.room(room.id, on: on) }
    }

    func setRoomLevel(_ room: Room, _ level: Int) {
        updateRoom(room.id) { if $0.dimmable && $0.on { $0.level = level } }
        send { try await $0.roomLevel(room.id, level) }
    }

    func setAll(on: Bool) {
        for room in state.rooms {
            updateRoom(room.id) { $0.on = on }
        }

        send { try await $0.all(on: on) }
    }

    func setLevel(_ light: Light, _ level: Int) {
        update(light.id) { $0.level = level }
        send { try await $0.batch(Batch(lights: [light.id], lightLevel: level)) }
    }

    func setTemperature(_ lights: [Light], _ kelvin: Int) {
        for light in lights {
            update(light.id) { $0.colorMode = false; $0.kelvin = kelvin; $0.hue = nil; $0.saturation = nil }
        }

        send { try await $0.batch(Batch(lights: lights.map(\.id), colorTemperature: kelvin)) }
    }

    func setColor(_ lights: [Light], hue: Double, saturation: Double) {
        let colorLights = lights.filter(\.supportsColor)

        for light in colorLights {
            update(light.id) { $0.colorMode = true; $0.hue = hue; $0.saturation = saturation; $0.kelvin = nil }
        }

        if !colorLights.isEmpty {
            send { try await $0.batch(Batch(lights: colorLights.map(\.id), colorHue: hue, colorSaturation: saturation)) }
        }
    }

    /// A palette chip on some lights: a temperature, a color, or a color set dealt out across the color lights
    func apply(_ preset: Preset, to lights: [Light]) {
        if let kelvin = preset.kelvin {
            setTemperature(lights, kelvin)
        }
        else if let hex = preset.hex, let (hue, saturation) = hueSaturation(hex) {
            setColor(lights, hue: hue, saturation: saturation)
        }
        else if let hexes = preset.hexes, !hexes.isEmpty {
            for (index, light) in lights.filter(\.supportsColor).enumerated() {
                if let (hue, saturation) = hueSaturation(hexes[index % hexes.count]) {
                    setColor([light], hue: hue, saturation: saturation)
                }
            }
        }
    }

    func applyScene(_ scene: SceneInfo) {
        send { try await $0.applyScene(scene.slug) }
    }

    func stopFades() {
        send { try await $0.cancelFades() }
    }

    // MARK: Plumbing

    private func send(_ command: @escaping (API) async throws -> Void) {
        holdPollingUntil = Date().addingTimeInterval(2)

        Task {
            do {
                try await command(API.current)
                error = nil
            }
            catch {
                self.error = error.localizedDescription
            }

            try? await Task.sleep(for: .seconds(2))
            await refresh()
        }
    }

    private func update(_ lightID: String, _ change: (inout Light) -> Void) {
        for r in state.rooms.indices {
            for l in state.rooms[r].lights.indices where state.rooms[r].lights[l].id == lightID {
                change(&state.rooms[r].lights[l])
            }
        }
    }

    private func updateRoom(_ roomID: String, _ change: (inout Light) -> Void) {
        for r in state.rooms.indices where state.rooms[r].id == roomID {
            for l in state.rooms[r].lights.indices where state.rooms[r].lights[l].reachable {
                change(&state.rooms[r].lights[l])
            }
        }
    }

    func light(_ id: String) -> Light? {
        state.rooms.lazy.flatMap(\.lights).first { $0.id == id }
    }
}
