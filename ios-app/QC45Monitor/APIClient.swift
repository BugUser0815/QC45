import Foundation
import Security
import CryptoKit

struct APIClient {
    let baseURL: String
    let token: String
    let certificatePin: String

    func status() async throws -> StationStatus {
        let data = try await request(path: "/api/status", method: "GET", query: [])
        return try JSONDecoder().decode(StationStatus.self, from: data)
    }

    func start(connector: Int, idTag: String) async throws {
        _ = try await request(path: "/api/start", method: "POST", query: [URLQueryItem(name: "connector", value: String(connector)), URLQueryItem(name: "idTag", value: idTag)])
    }

    func stop(connector: Int) async throws {
        _ = try await request(path: "/api/stop", method: "POST", query: [URLQueryItem(name: "connector", value: String(connector))])
    }

    private func request(path: String, method: String, query: [URLQueryItem]) async throws -> Data {
        guard baseURL.lowercased().hasPrefix("https://") else { throw NSError(domain: "QC45Monitor", code: -10, userInfo: [NSLocalizedDescriptionKey: "Nur HTTPS ist erlaubt."]) }
        let normalizedPin = certificatePin.replacingOccurrences(of: ":", with: "").replacingOccurrences(of: " ", with: "").uppercased()
        guard normalizedPin.count == 64 else { throw NSError(domain: "QC45Monitor", code: -11, userInfo: [NSLocalizedDescriptionKey: "Ungültiger SHA-256 Zertifikat-Pin."]) }

        guard var components = URLComponents(string: baseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path) else { throw URLError(.badURL) }
        components.queryItems = query.isEmpty ? nil : query
        guard let url = components.url else { throw URLError(.badURL) }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 8
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let delegate = PinnedCertificateDelegate(expectedSHA256: normalizedPin)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let text = String(data: data, encoding: .utf8) ?? ""
            throw NSError(domain: "QC45Monitor", code: (response as? HTTPURLResponse)?.statusCode ?? -1, userInfo: [NSLocalizedDescriptionKey: text.isEmpty ? "Serverfehler" : text])
        }
        return data
    }
}

final class PinnedCertificateDelegate: NSObject, URLSessionDelegate {
    private let expectedSHA256: String
    init(expectedSHA256: String) { self.expectedSHA256 = expectedSHA256 }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              let certificate = SecTrustGetCertificateAtIndex(trust, 0) else {
            completionHandler(.cancelAuthenticationChallenge, nil); return
        }
        let der = SecCertificateCopyData(certificate) as Data
        let digest = SHA256.hash(data: der).map { String(format: "%02X", $0) }.joined()
        guard digest == expectedSHA256 else {
            completionHandler(.cancelAuthenticationChallenge, nil); return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }
}
