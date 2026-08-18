import Foundation

struct APIClient {
    let baseURL: String
    let token: String

    func status() async throws -> StationStatus {
        let data = try await request(path: "/api/status", method: "GET", query: [])
        return try JSONDecoder().decode(StationStatus.self, from: data)
    }

    func start(connector: Int, idTag: String) async throws {
        _ = try await request(path: "/api/start", method: "POST", query: [
            URLQueryItem(name: "connector", value: String(connector)),
            URLQueryItem(name: "idTag", value: idTag)
        ])
    }

    func stop(connector: Int) async throws {
        _ = try await request(path: "/api/stop", method: "POST", query: [
            URLQueryItem(name: "connector", value: String(connector))
        ])
    }

    private func request(path: String, method: String, query: [URLQueryItem]) async throws -> Data {
        guard var components = URLComponents(string: baseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path) else {
            throw URLError(.badURL)
        }
        components.queryItems = query.isEmpty ? nil : query
        guard let url = components.url else { throw URLError(.badURL) }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 8
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let text = String(data: data, encoding: .utf8) ?? ""
            throw NSError(domain: "QC45Monitor", code: (response as? HTTPURLResponse)?.statusCode ?? -1,
                          userInfo: [NSLocalizedDescriptionKey: text.isEmpty ? "Serverfehler" : text])
        }
        return data
    }
}
