import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Ladestation") {
                    TextField("API URL", text: $settings.baseURL)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                    SecureField("Bearer Token", text: $settings.token)
                        .textInputAutocapitalization(.never)
                }
                Section("Beispiel") {
                    Text("http://10.0.0.156:9080")
                        .font(.caption.monospaced())
                    Text("Für Zugriff aus dem Internet VPN oder HTTPS-Reverse-Proxy verwenden.")
                        .font(.footnote)
                }
            }
            .navigationTitle("Einstellungen")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
    }
}
