import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var settings: AppSettings
    @State private var status: StationStatus?
    @State private var error: String?
    @State private var showingSettings = false
    @State private var showingStart = false
    @State private var selectedConnector = 2
    @State private var idTag = ""

    var body: some View {
        NavigationStack {
            Group {
                if let status {
                    ScrollView {
                        VStack(spacing: 16) {
                            header(status)
                            gridCard(status.grid)
                            ForEach(status.connectors) { connector in connectorCard(connector) }
                        }
                        .padding()
                    }
                    .refreshable { await refresh() }
                } else if let error {
                    ContentUnavailableView("QC45 nicht erreichbar", systemImage: "wifi.exclamationmark", description: Text(error))
                } else {
                    ProgressView("Verbinde mit QC45 …")
                }
            }
            .navigationTitle("QC45 Monitor")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button { Task { await refresh() } } label: { Image(systemName: "arrow.clockwise") } }
                ToolbarItem(placement: .topBarTrailing) { Button { showingSettings = true } label: { Image(systemName: "gearshape") } }
            }
            .task { await refreshLoop() }
            .sheet(isPresented: $showingSettings) { SettingsView() }
            .sheet(isPresented: $showingStart) { startSheet }
        }
    }

    private func header(_ s: StationStatus) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack { Label(s.online ? "Online" : "Offline", systemImage: s.online ? "checkmark.circle.fill" : "xmark.circle.fill"); Spacer(); Text("\(s.stationPowerKw) kW").font(.title2.bold()) }
            HStack { Label(s.ocppConnected ? "OCPP verbunden" : "OCPP getrennt", systemImage: "network"); Spacer(); if s.meterPaused { Label("KSEM Pause", systemImage: "pause.circle.fill") } else if s.failbackTripped { Label("Failback", systemImage: "exclamationmark.triangle.fill") } }
        }
        .padding().background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private func gridCard(_ g: GridStatus) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack { Text("Netz / KSEM").font(.headline); Spacer(); Text(g.online ? "verbunden" : "offline") }
            HStack { phase("L1", g.l1); Spacer(); phase("L2", g.l2); Spacer(); phase("L3", g.l3) }
            Text("Höchste Phase: \(g.max, specifier: "%.1f") A").font(.footnote).foregroundStyle(.secondary)
        }
        .padding().background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private func phase(_ name: String, _ value: Double) -> some View { VStack { Text(name).font(.caption).foregroundStyle(.secondary); Text("\(value, specifier: "%.1f") A").font(.headline) } }

    private func connectorCard(_ c: ConnectorStatus) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack { Text(c.name).font(.headline); Spacer(); Text(c.active ? "Aktiv" : "Bereit") }
            HStack(alignment: .firstTextBaseline) {
                Text("\(c.powerKw)").font(.system(size: 36, weight: .bold, design: .rounded)); Text("kW").foregroundStyle(.secondary); Spacer()
                VStack(alignment: .trailing) { Text("Limit \(c.limitKw) kW"); if !c.idTag.isEmpty { Text(c.idTag).font(.caption.monospaced()).foregroundStyle(.secondary) } }
            }
            HStack { Button("Remote Start") { selectedConnector = c.id; showingStart = true }.buttonStyle(.borderedProminent); Spacer(); Button("Stop", role: .destructive) { Task { await stop(c.id) } }.buttonStyle(.bordered).disabled(!c.active) }
        }
        .padding().background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private var startSheet: some View {
        NavigationStack {
            Form {
                Picker("Connector", selection: $selectedConnector) { Text("CHAdeMO").tag(1); Text("CCS").tag(2); Text("Type 2").tag(3) }
                TextField("RFID / idTag", text: $idTag).textInputAutocapitalization(.characters)
                Button("Ladevorgang starten") { Task { await start(); showingStart = false } }.disabled(idTag.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .navigationTitle("Remote Start")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { showingStart = false } } }
        }
    }

    private var api: APIClient { APIClient(baseURL: settings.baseURL, token: settings.token, certificatePin: settings.certificatePin) }

    @MainActor private func refresh() async { do { status = try await api.status(); error = nil } catch { self.error = error.localizedDescription; status = nil } }
    private func refreshLoop() async { while !Task.isCancelled { await refresh(); try? await Task.sleep(for: .seconds(3)) } }
    @MainActor private func start() async { do { try await api.start(connector: selectedConnector, idTag: idTag); await refresh() } catch { self.error = error.localizedDescription } }
    @MainActor private func stop(_ connector: Int) async { do { try await api.stop(connector: connector); await refresh() } catch { self.error = error.localizedDescription } }
}
