import AppIntents

/// A scene as Siri, Shortcuts and Control Center see it. Scenes are user-made and
/// live on the server, so the entity list is fetched from /api/state each time.

struct SceneEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Scene"
    static let defaultQuery = SceneQuery()

    var id: String
    var name: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)")
    }

    init(id: String, name: String) {
        self.id = id
        self.name = name
    }

    init(_ scene: SceneInfo) {
        self.init(id: scene.slug, name: scene.name)
    }
}

struct SceneQuery: EntityQuery, EntityStringQuery {
    func entities(for identifiers: [String]) async throws -> [SceneEntity] {
        try await all().filter { identifiers.contains($0.id) }
    }

    func entities(matching string: String) async throws -> [SceneEntity] {
        try await all().filter { $0.name.localizedCaseInsensitiveContains(string) }
    }

    func suggestedEntities() async throws -> [SceneEntity] {
        try await all()
    }

    private func all() async throws -> [SceneEntity] {
        try await API.current.state().scenes.map(SceneEntity.init)
    }
}

struct ApplySceneIntent: AppIntent {
    static let title: LocalizedStringResource = "Apply Scene"
    static let description = IntentDescription("Puts the lights in a saved HomeControl scene.")

    @Parameter(title: "Scene")
    var scene: SceneEntity

    init() {}

    init(scene: SceneEntity) {
        self.scene = scene
    }

    static var parameterSummary: some ParameterSummary {
        Summary("Apply \(\.$scene)")
    }

    func perform() async throws -> some IntentResult {
        try await API.current.applyScene(scene.id)
        return .result()
    }
}

struct AllLightsIntent: AppIntent {
    static let title: LocalizedStringResource = "Turn All Lights On or Off"
    static let description = IntentDescription("Turns every light in the house on or off.")

    @Parameter(title: "On", default: false)
    var on: Bool

    init() {}

    init(on: Bool) {
        self.on = on
    }

    static var parameterSummary: some ParameterSummary {
        Summary("Turn all lights \(\.$on)")
    }

    func perform() async throws -> some IntentResult {
        try await API.current.all(on: on)
        return .result()
    }
}

struct StopFadesIntent: AppIntent {
    static let title: LocalizedStringResource = "Stop Scheduled Fades"
    static let description = IntentDescription("Stops any running scheduled transition, leaving the lights where they are.")

    func perform() async throws -> some IntentResult {
        try await API.current.cancelFades()
        return .result()
    }
}
