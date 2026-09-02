# OCPP Bridge

Die produktive Integration lässt das alte EVCSD weiterhin **OCPP 1.5 SOAP** sprechen, setzt davor aber einen lokalen Übersetzer auf **OCPP 1.6 JSON über WSS** zum ChargePoint-Backend.

## Datenpfad

```mermaid
flowchart LR
  E[EVCSD OCPP 1.5 SOAP] -->|HTTP 127.0.0.1:9000/QC45| B[Ocpp15BridgeServer]
  B --> C[OcppBridgeClient]
  C -->|TLS 1.2 / WebSocket / ocpp1.6| CP[ChargePoint]
  CP -->|RemoteStart / RemoteStop| C
  C --> R[ReflectionQC45]
```

## Standard-Loopback

```text
Bind: 127.0.0.1
Port: 9000
Path: /QC45
```

Damit bleibt die Legacy-Schnittstelle ausschließlich lokal erreichbar.

## SOAP → JSON weitergeleitete Operationen

Der aktuelle Bridge-Server behandelt unter anderem:

- BootNotification
- Heartbeat
- Authorize
- StatusNotification
- StartTransaction
- MeterValues
- StopTransaction
- FirmwareStatusNotification
- DiagnosticsStatusNotification
- DataTransfer

Transaktions-IDs werden dem jeweiligen Connector zugeordnet und atomar in
`ocpp.transactionMapFile` persistiert. Damit bleibt ein
Backend-`RemoteStopTransaction` auch nach einem Webapp-/JVM-Neustart auflösbar.
Fehlt ein gespeicherter Eintrag, versucht die Bridge die ID aus der laufenden
EVCSD-Transaktion zu lesen; nur bei genau einer aktiven Session ist zusätzlich
ein eindeutiger Fallback zulässig.

Bei `MeterValues` werden sämtliche Zeitgruppen und sämtliche enthaltenen
Messwerte übernommen – einschließlich Measurand, Phase, Kontext, Format,
Position und Einheit. Die frühere Beschränkung auf den ersten Wert ist entfernt.

Periodische Messwerte, die das alte QC45-EVCSD ohne `measurand` und `unit`
liefert, werden als `Power.Active.Import` in `kW` mit dem Kontext
`Sample.Periodic` ergänzt. Das verhindert, dass OCPP-1.6-Backends die
momentane Ladeleistung standardmäßig als Energiezähler in `Wh` interpretieren.
Explizit gekennzeichnete Energiezähler sowie `meterStart` und `meterStop`
bleiben unverändert.

## Statuskorrektur

Das alte EVCSD meldet Zustände nicht immer passend zur OCPP-1.6-Semantik. Die Bridge leitet deshalb Status aus Livezuständen ab:

- Leistung > 0 → `Charging`
- aktive Transaktion, Limit = 0 → `SuspendedEVSE`
- aktive Transaktion, keine Leistung, Limit > 0 → `SuspendedEV`
- nach Stop → best effort `Finishing`

Ein eingehendes OCPP-1.5-`Occupied` wird in Richtung 1.6 zunächst als `Preparing` behandelt.

## Backend-Kommandos

`OcppBridgeClient` akzeptiert aktuell:

| OCPP 1.6 CALL | Verhalten |
|---|---|
| `RemoteStartTransaction` | an `ReflectionQC45.remoteStart()` |
| `RemoteStopTransaction` | anhand gemerkter Transaktion an richtigen Connector |
| `Reset` | `Rejected` |
| `UnlockConnector` | `NotSupported` |

## Transport

- ausschließlich `wss://`
- TLS 1.2
- Basic Authentication
- WebSocket Subprotocol `ocpp1.6`
- CA-Datei konfigurierbar
- optional `tls.insecure=true` für Diagnose, nicht für Normalbetrieb empfohlen
- Reconnect mit exponentiellem Backoff bis 30 s
- Prüfung der TLS-Hostname-Identität und des vom Backend bestätigten
  `ocpp1.6`-Subprotokolls
- Reassembly fragmentierter Textnachrichten bis maximal 1 MiB

Der lokale SOAP-Endpunkt darf ausschließlich an eine Loopback-Adresse binden,
begrenzt Requests auf 1 MiB und nutzt einen begrenzten Vier-Thread-Executor.
Beim Shutdown werden HTTP-Executor und WebSocket-Thread beendet.

Quellcode:

- `https://github.com/BugUser0815/QC45/blob/native-integration/native-integration/src/main/java/de/rothner/qc45/Ocpp15BridgeServer.java`
- `https://github.com/BugUser0815/QC45/blob/native-integration/native-integration/src/main/java/de/rothner/qc45/OcppBridgeClient.java`

Siehe auch: [RemoteStart & Autorisierung](RemoteStart-und-Autorisierung).
