# Native Integration

## Version 2.0

Freigegeben am **2. Oktober 2026**. Maßgeblicher Freigabestand vor den reinen Dokumentationscommits: `2487b7b1ea5b21338b6394f12199b1cf0a377020`.

Die native Integration ersetzt mehrere frühere externe Hilfsprozesse durch ein Java-7-JAR innerhalb derselben Tomcat/JVM wie EVCSD.

## Ziel

- direkter Zugriff auf `CentralModule`, `SatelliteModule` und `Configuration`,
- kein separater Python-OCPP-Prozess,
- lokaler Modbus/TCP-Endpunkt für evcc und UI,
- KSEM-basierte DC-Leistungsregelung,
- DC-Failback und Netzschutz direkt an der Stationslogik,
- AC-Telemetrie ohne Eingriff in die Type-2-Leistungssteuerung,
- Diagnose und persistentes Logging direkt auf der QC45.

## Architektur Version 2.0

```text
                         ChargePoint
                             ^
                             | OCPP
                             |
                   qc45-integration.jar
                    /        |         \
                   /         |          \
              Modbus       KSEM       Diagnose
             /   |           |             |
          evcc  UI       DC-Regelung       Logs
                           |
                     CCS / CHAdeMO

Type 2 AC:
QC45 Originalsteuerung -> feste AC22-Obergrenze -> Fahrzeug
                         |
                         +-> Integration liest Zustand/Leistung/Energie
```

Der wichtigste Architekturpunkt von Version 2.0 ist die bewusste Trennung von AC und DC:

- **DC:** aktiv durch die SGS-Integration geregelt.
- **AC:** original durch die QC45 geregelt; SGS liest nur Telemetrie.

## Von `Integration` gestartete Komponenten

| Komponente | Funktion in Version 2.0 |
|---|---|
| `ReflectionQC45` | Adapter auf laufende EVCSD-Objekte |
| `ChargingLimitCoordinator` | Koordination der aktiv geregelten DC-Limits |
| `ChargingLimitGuard` | DC-Reconcile und Schutzlogik |
| `ModbusServer` | Modbus/TCP für evcc, UI und Telemetrie |
| `OcppBridgeClient` | OCPP 1.6 JSON/WSS zum Backend |
| `Ocpp15BridgeServer` | lokale OCPP-1.5-SOAP-zu-1.6-Bridge |
| `LoadManager` | KSEM-basierte DC-Leistungsfreigabe |
| `GridFailback` | unabhängige DC-Schutzebene am Netzlimit |
| `AcPowerTelemetry` | lesende Type-2-Leistungs-/Energietelemetrie |
| `SafetyDiagnostics` | Diagnose der Stations- und Schutzwerte |
| `RemoteStartAuthorizationFix` | connectorbezogene Remote-Autorisierung |

Historische AC-Steuerklassen können weiterhin im Quellbaum vorhanden sein, sind aber nicht Teil der freigegebenen AC-Regelstrategie.

## Type 2 AC

Die QC45 ist im aktuellen Betrieb auf **AC22 / maximal 22 kW** eingestellt. Die native Integration setzt diesen Wert nicht selbst und schreibt keine AC-Leistungsgrenzen.

Die Integration liest weiterhin:

- Sitzungszustand,
- aktuelle Leistung,
- Energie,
- diagnostische Zustände.

Diese Werte stehen anschließend in Stationsanzeige, Modbus, evcc und Logs zur Verfügung.

Der reale Abnahmetest am 2. Oktober 2026 mit einem Tesla Model Y zeigte ungefähr 11 kW gleichzeitig korrekt an der QC45 und in evcc. Der Ladevorgang blieb stabil.

Details: [Type 2 / AC-Leistungsbegrenzung](Type2-AC-Leistungsbegrenzung).

## DC-Regelung

DC bleibt vollständig unter Kontrolle der nativen Integration. Der aktuelle Stand enthält insbesondere:

- KSEM-Auswertung,
- dynamische DC-Vorfreigabe,
- 5-kW-Notladen/Fallback,
- Begrenzung nach verfügbarer Netzreserve,
- Failback und Hard-Trip-Logik,
- evcc-Modbus-Anbindung,
- CCS-/CHAdeMO-Telemetrie.

Die AC-Entkopplung verändert diese DC-Regelung nicht.

## Reflection statt Firmware-Patch

`ReflectionQC45` greift auf bereits im Original-EVCSD vorhandene Objekte zu. Die Original-JARs bleiben grundsätzlich unangetastet; die Anpassung wird als zusätzliche Bibliothek in `WEB-INF/lib` geladen.

Verwendet werden unter anderem:

- Leistungs- und Energiewerte der Satelliten,
- aktive Transaktionen,
- `CentralModule`-Zustände,
- `SatelliteInfo`,
- relevante `Configuration`-Felder.

## Persistentes Logging

Die native Integration schreibt nach:

```text
/home/mobie/evcsd/qc45-integration.log
```

Die Original-EVCSD-Logs liegen separat unter dem nativen Stationslogpfad. Auf `hostserver1` spiegelt der QC45-Log-Relay aktuelle Ausschnitte regelmäßig in das Repository `sgs-infrastructure` auf den Branch `qc45-live-logs`.

Damit können reale Ladevorgänge ohne direkten SSH-Zugriff nachträglich geprüft werden.

## Modbus / evcc

Modbus TCP stellt sowohl Steuerwerte für DC als auch Telemetrie für AC/DC bereit. In Version 2.0 ist wichtig:

- DC-Schreibpfade können die wirksame DC-Freigabe beeinflussen.
- AC-Werte dienen der Anzeige und Diagnose; daraus folgt keine physische AC-Leistungsregelung.

Damit kann evcc die tatsächliche Type-2-Leistung darstellen, obwohl die Leistungsobergrenze ausschließlich von der QC45 selbst vorgegeben wird.

## Freigabegrundsatz

> Änderungen an der AC-Steuerung dürfen nicht versehentlich wieder über KSEM, evcc, LoadManager oder eigene MobiBus-Limitpfade aktiviert werden. Version 2.0 verwendet Type 2 bewusst read-only aus Sicht der SGS-Regelung.

Für DC gilt weiterhin: Änderungen an Regelung, Failback und Sicherheitsgrenzen müssen zuerst auf `native-integration` getestet werden, bevor sie auf `main` übernommen werden.

Siehe auch:

- `RELEASE-2.0.md`
- `native-integration/README.md`
- [Type 2 / AC-Leistungsbegrenzung](Type2-AC-Leistungsbegrenzung)
- [Modbus TCP](Modbus-TCP)
- [OCPP Bridge](OCPP-Bridge)
