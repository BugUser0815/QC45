import Foundation
import Security

struct StationStatus: Codable {
    let online: Bool
    let ocppConnected: Bool
    let remoteStarted: Bool
    let failbackTripped: Bool
    let meterPaused: Bool
    let stationPowerKw: Int
    let grid: GridStatus
    let connectors: [ConnectorStatus]
}

struct GridStatus: Codable {
    let online: Bool
    let l1: Double
    let l2: Double
    let l3: Double
    let max: Double
}

struct ConnectorStatus: Codable, Identifiable {
    let id: Int
    let name: String
    let powerKw: Int
    let limitKw: Int
    let energyRaw: Int64
    let active: Bool
    let idTag: String
}

@MainActor
final class AppSettings: ObservableObject {
    @Published var baseURL: String {
        didSet { UserDefaults.standard.set(baseURL, forKey: "baseURL") }
    }
    @Published var token: String {
        didSet { Keychain.save(token, key: "remoteApiToken") }
    }

    init() {
        baseURL = UserDefaults.standard.string(forKey: "baseURL") ?? "http://10.0.0.156:9080"
        token = Keychain.load(key: "remoteApiToken") ?? ""
    }
}

enum Keychain {
    static func save(_ value: String, key: String) {
        let data = Data(value.utf8)
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrAccount as String: key]
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = data
        SecItemAdd(add as CFDictionary, nil)
    }

    static func load(key: String) -> String? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrAccount as String: key,
                                    kSecReturnData as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitOne]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
