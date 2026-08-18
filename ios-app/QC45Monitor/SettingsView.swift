import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Ladestation") {
                    TextField("HTTPS API URL", text: $settings.baseURL)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                    SecureField("Bearer Token", text: $settings.token)
                        .textInputAutocapitalization(.never)
                    TextField("SHA-256 Zertifikat-Pin", text: $settings.certificatePin)
                        .textInputAutocapitalization(.characters)
                        .font(.caption.monospaced())
                }
                Section("Direktzugriff") {
                    Text("https://dahoam.sgs-elektro.de")
                        .font(.caption.monospaced())
                    Text("Die App akzeptiert nur das exakt gepinnte Serverzertifikat. Ein anderes Zertifikat wird abgelehnt.")
                        .font(.footnote)
                }
            }
            .navigationTitle("Einstellungen")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
    }
}
