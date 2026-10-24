import Foundation

/// The HomeControl server's JSON API. All the hub knowledge (throttling, fades,
/// bulb quirks, schedules) lives on the server; this only says what we want.

struct API {
    var baseURL: URL

    static var current: API { API(baseURL: Settings.serverURL) }

    enum Failure: LocalizedError {
        case status(Int, String)

        var errorDescription: String? {
            switch self {
            case let .status(code, message): "Server answered \(code): \(message)"
            }
        }
    }

    func state() async throws -> HouseState {
        try JSONDecoder().decode(HouseState.self, from: try await send("GET", "/api/state"))
    }

    func all(on: Bool) async throws {
        try await post("/api/all/\(on ? "on" : "off")")
    }

    func room(_ roomID: String, on: Bool) async throws {
        try await post("/api/room/\(segment(roomID))/\(on ? "on" : "off")")
    }

    func roomLevel(_ roomID: String, _ level: Int) async throws {
        try await post("/api/room-level/\(segment(roomID))/\(level)")
    }

    func toggle(_ lightID: String) async throws {
        try await post("/api/toggle/\(segment(lightID))")
    }

    func applyScene(_ slug: String) async throws {
        try await post("/api/scene/\(segment(slug))")
    }

    func batch(_ batch: Batch) async throws {
        try await send("POST", "/api/batch", body: try JSONEncoder().encode(batch))
    }

    /// Stops every running scheduled fade; the lights stay where they are
    func cancelFades() async throws {
        try await post("/api/schedule/cancel")
    }

    private func post(_ path: String) async throws {
        try await send("POST", path)
    }

    @discardableResult
    private func send(_ method: String, _ path: String, body: Data? = nil) async throws -> Data {
        // Appending the path as a string keeps our percent-encoding (URL.appending would encode it again)
        var request = URLRequest(url: URL(string: baseURL.absoluteString.trimmingSuffix("/") + path)!)
        request.httpMethod = method
        request.timeoutInterval = 15

        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "content-type")
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0

        guard (200..<300).contains(status) else {
            throw Failure.status(status, String(decoding: data, as: UTF8.self))
        }

        return data
    }

    /// Scene slugs can be Icelandic (nótt) — the server decodes each path component
    private func segment(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? value
    }
}

private extension String {
    func trimmingSuffix(_ suffix: String) -> String {
        hasSuffix(suffix) ? String(dropLast(suffix.count)) : self
    }
}
