import Foundation

/// The house as served by GET /api/state (see StateFeed.java on the server).
/// hue/saturation are only present while a bulb is in color mode, kelvin only
/// while it's in temperature mode — the hub's values for the inactive mode are stale.

struct HouseState: Codable, Equatable {
    var rooms: [Room]
    var scenes: [SceneInfo]
    var palette: [Preset]
    var warnings: [String]
    var runningFades: Int

    static let empty = HouseState(rooms: [], scenes: [], palette: [], warnings: [], runningFades: 0)
}

struct Room: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var hub: String?
    var lights: [Light]

    var anyOn: Bool { lights.contains { $0.on && $0.reachable } }
}

struct Light: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var on: Bool
    var reachable: Bool
    var dimmable: Bool
    var level: Int?
    var supportsTemperature: Bool
    var supportsColor: Bool
    var colorMode: Bool
    var kelvin: Int?
    var warmest: Int
    var coldest: Int
    var hue: Double?
    var saturation: Double?
}

struct SceneInfo: Codable, Equatable, Identifiable, Hashable {
    var name: String
    var slug: String
    var lightCount: Int
    var away: Bool

    var id: String { slug }
}

/// A palette chip: a single color (hex), a white temperature (kelvin) or a set of colors (hexes)
struct Preset: Codable, Equatable, Identifiable {
    var name: String
    var hex: String?
    var kelvin: Int?
    var hexes: [String]?
    var custom: Bool

    var id: String { name }
}

/// The body of POST /api/batch — any attribute may be left out
struct Batch: Encodable {
    var lights: [String]
    var isOn: Bool? = nil
    var lightLevel: Int? = nil
    var colorTemperature: Int? = nil
    var colorHue: Double? = nil
    var colorSaturation: Double? = nil
}
