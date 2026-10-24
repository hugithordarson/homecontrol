import Foundation

/// Settings shared between the app and the Control Center extension through the app group
enum Settings {
    static let defaults = UserDefaults(suiteName: "group.is.rebbi.ljos") ?? .standard

    #if targetEnvironment(simulator)
    // The simulator runs on the dev Mac, next to the Eclipse-launched dev app
    static let defaultServer = "http://localhost:1200"
    #else
    static let defaultServer = "http://ljos.rebbi.is"
    #endif

    static var server: String {
        get { defaults.string(forKey: "server") ?? defaultServer }
        set { defaults.set(newValue, forKey: "server") }
    }

    static var serverURL: URL {
        URL(string: server) ?? URL(string: defaultServer)!
    }
}
