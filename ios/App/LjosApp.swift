import AppIntents
import SwiftUI

@main
struct LjosApp: App {
    @State private var house = House()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            HouseView()
                .environment(house)
                .task(id: scenePhase) {
                    if scenePhase == .active {
                        await house.poll()
                    }
                }
        }
    }
}

/// "Hey Siri, Ljós Nótt" and friends
struct LjosShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: ApplySceneIntent(),
            phrases: [
                "\(.applicationName) \(\.$scene)",
                "Apply \(\.$scene) in \(.applicationName)",
                "Set \(.applicationName) to \(\.$scene)",
            ],
            shortTitle: "Apply Scene",
            systemImageName: "sparkles")

        AppShortcut(
            intent: AllLightsIntent(on: false),
            phrases: [
                "Turn off all lights with \(.applicationName)",
                "\(.applicationName) all off",
            ],
            shortTitle: "All Lights Off",
            systemImageName: "lightbulb.slash")

        AppShortcut(
            intent: AllLightsIntent(on: true),
            phrases: [
                "Turn on all lights with \(.applicationName)",
                "\(.applicationName) all on",
            ],
            shortTitle: "All Lights On",
            systemImageName: "lightbulb.fill")
    }
}
