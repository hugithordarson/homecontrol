import AppIntents
import SwiftUI
import WidgetKit

/// Control Center (and Lock Screen / Action button) controls

@main
struct LjosControls: WidgetBundle {
    var body: some Widget {
        SceneControl()
        AllOffControl()
    }
}

/// A button for one scene — the scene is picked when the control is added
struct SceneControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        AppIntentControlConfiguration(kind: "is.rebbi.ljos.scene", intent: SceneControlConfiguration.self) { configuration in
            ControlWidgetButton(action: ApplySceneIntent(scene: configuration.scene ?? SceneEntity(id: "", name: "Scene"))) {
                Label(configuration.scene?.name ?? "Choose scene", systemImage: "sparkles")
            }
        }
        .displayName("Scene")
        .description("Applies a HomeControl scene.")
        .promptsForUserConfiguration()
    }
}

struct SceneControlConfiguration: ControlConfigurationIntent {
    static let title: LocalizedStringResource = "Scene"

    @Parameter(title: "Scene")
    var scene: SceneEntity?

    func perform() async throws -> some IntentResult {
        .result()
    }
}

struct AllOffControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "is.rebbi.ljos.all-off") {
            ControlWidgetButton(action: AllLightsIntent(on: false)) {
                Label("All lights off", systemImage: "lightbulb.slash")
            }
        }
        .displayName("All Lights Off")
        .description("Turns off every light in the house.")
    }
}
