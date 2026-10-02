# QC45 Integration Version 2.0

Freigabedatum: 2. Oktober 2026

Freigabestand: `2487b7b1ea5b21338b6394f12199b1cf0a377020`

Version 2.0 bezeichnet den ersten im praktischen Betrieb freigegebenen Stand der nativen SGS-QC45-Integration. Der zuvor auf `native-integration` entwickelte Stand wurde nach erfolgreicher Prüfung als Fast-Forward auf `main` übernommen.

## Freigabestatus

- `main` und `native-integration` waren zum Freigabezeitpunkt identisch.
- Der Übergang auf `main` erfolgte als sauberer Fast-Forward ohne Force-Push.
- Der Stand enthält die native Java-Integration, OCPP-Bridge, Modbus/evcc-Telemetrie, KSEM-basierte DC-Regelung, Netzschutz/Failback und das angepasste lokale UI.

## AC / Type 2

Für Version 2.0 gilt verbindlich:

- Type 2 wird von der SGS-Integration **nicht mehr leistungsgeregelt**.
- Die AC-Leistungsgrenze wird ausschließlich in der originalen QC45-Konfiguration gesetzt.
- Betriebswert an der Station: **AC22 / 22 kW maximal**.
- Die native Integration schreibt keine AC-Leistungsgrenzen und greift nicht in den AC-Load-Balancing-Pfad ein.
- AC wird weiterhin lesend überwacht: Sitzungszustand, Leistung und Energie stehen für Diagnose, Modbus, Dashboard und evcc zur Verfügung.
- Die tatsächlich dargestellte Leistung folgt der Fahrzeugaufnahme und nicht der konfigurierten 22-kW-Obergrenze.

### Abnahmetest 2. Oktober 2026

Die Version wurde mit einem Tesla Model Y im laufenden Type-2-Betrieb geprüft:

- Fahrzeugaufnahme etwa **11 kW**,
- Anzeige an der QC45 etwa **11 kW**,
- Anzeige in evcc etwa **11 kW**,
- stabiler Ladevorgang ohne die zuvor beobachtete Start/Stop-Oszillation,
- keine auffälligen Fehler- oder EPO-Zustände im geprüften Logfenster.

Damit ist zugleich die überarbeitete AC-Telemetrie im realen Betrieb bestätigt: Eine Station mit 22-kW-Maximum zeigt bei einem Fahrzeug mit 11-kW-Aufnahme die tatsächlichen rund 11 kW an.

## DC

DC bleibt aktiv durch die native Integration geregelt. Version 2.0 enthält insbesondere:

- KSEM-basierte Leistungsfreigabe,
- dynamische DC-Vorfreigabe,
- 5-kW-Notladen/Fallback,
- Peak-/Netzschutzlogik für den 35-A-Anschluss,
- evcc/Modbus-Anbindung,
- CCS-/CHAdeMO-Telemetrie,
- OCPP-Anbindung an das Backend.

Die AC-Entkopplung ändert an der DC-Regelung nichts.

## Hintergrund der AC-Entscheidung

Frühere Versuche, die Type-2-Leistung über die native Integration beziehungsweise den originalen MobiBus-Lastverteilungspfad dynamisch zu verändern, führten bei realen Fahrzeugen zu unzuverlässigem Verhalten, unter anderem zu kurzen Schützfreigaben mit anschließendem Ladeabbruch. Deshalb wurde die AC-Leistungsregelung bewusst aus der SGS-Integration entfernt.

Die stabile Betriebsarchitektur von Version 2.0 lautet daher:

```text
Type 2 AC:
QC45 Originalsteuerung -> feste AC22-Obergrenze -> Fahrzeug
                         |
                         +-> SGS Integration liest Leistung/Zustand -> UI / Modbus / evcc

DC:
KSEM -> SGS LoadManager / Failback -> QC45 DC-Leistungsfreigabe -> Fahrzeug
```

## Versionshinweis

„Version 2.0“ ist die freigegebene System-/Integrationsversion. Der Maven-Artefaktname kann intern weiterhin eine ältere technische Paketversionsnummer enthalten; maßgeblich für die Freigabe ist der oben genannte Git-Commit auf `main`.

Weitere Details:

- `wiki/Type2-AC-Leistungsbegrenzung.md`
- `wiki/Native-Integration.md`
- `native-integration/README.md`
