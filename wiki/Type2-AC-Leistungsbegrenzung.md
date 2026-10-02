# Type 2 / AC-Leistungsbegrenzung

## Stand Version 2.0 – freigegeben am 2. Oktober 2026

Für den freigegebenen Version-2.0-Stand gilt ein klarer Grundsatz:

> **Die SGS-QC45-Integration regelt Type 2 AC nicht mehr.**

Die Leistungsobergrenze wird ausschließlich in der originalen QC45-Konfiguration festgelegt. Die Station ist für den aktuellen Betrieb auf **AC22 / maximal 22 kW** eingestellt. Die tatsächliche Fahrzeugaufnahme darf darunter liegen und wird von der Integration weiterhin gelesen und angezeigt.

## Aktuelle Architektur

```text
QC45 Originalsteuerung
        |
        +-- feste AC22-Obergrenze
        |
        +--> Type-2-Fahrzeug
               |
               +--> tatsächliche Leistung / Energie
                         |
                         +--> SGS Telemetrie
                               +--> Stationsanzeige
                               +--> Modbus
                               +--> evcc
                               +--> Diagnose/Logs
```

Die native Integration:

- startet keine eigene AC-Leistungsregelung,
- schreibt keine Type-2-Leistungsgrenzen,
- greift nicht in die originale AC-Lastverteilung ein,
- verwendet KSEM/LoadManager nicht zur AC-Leistungssteuerung,
- verwendet evcc nicht zur AC-Leistungssteuerung,
- liest AC-Leistung, Energie und Sitzungszustand weiterhin aus,
- stellt diese Werte für UI, Modbus, evcc und Diagnose bereit.

Die im Dashboard sichtbare tatsächliche AC-Leistung ist daher **kein Sollwert**. Sie zeigt die reale Leistungsaufnahme des Fahrzeugs innerhalb der von der QC45 vorgegebenen Obergrenze.

## Abnahmetest Version 2.0

Am 2. Oktober 2026 wurde der freigegebene Stand mit einem Tesla Model Y im laufenden Type-2-Betrieb geprüft.

Ergebnis:

- Fahrzeugaufnahme etwa 11 kW,
- QC45-Anzeige etwa 11 kW,
- evcc-Anzeige etwa 11 kW,
- stabiler Ladevorgang,
- keine zuvor beobachtete Schütz-/Start-Stop-Oszillation,
- keine auffälligen Fehler- oder EPO-Zustände im geprüften Logfenster.

Damit ist auch die AC-Telemetrie praktisch bestätigt: Die auf 22 kW konfigurierte Station zeigt bei einem Fahrzeug, das nur rund 11 kW aufnimmt, die reale Leistung und nicht pauschal die konfigurierte Obergrenze.

## Warum die Integration AC nicht mehr regelt

Während der Entwicklung wurden mehrere Ansätze untersucht, um die Type-2-Leistung dynamisch zu beeinflussen:

- Java-seitige Satelliten-Limits,
- `Configuration.maxPowerAC`,
- originale AC-Load-Balance-Pfade,
- MobiBus `START_CHARGE` / `ENERGY`,
- eigene AC-Limit-Transporte,
- logisches Notladen mit reduzierter Leistung.

Bei realen Fahrzeugtests zeigte sich, dass diese Wege auf der vorhandenen QC45-Firmware nicht zuverlässig genug sind. Unter anderem traten Ladeversuche auf, bei denen das Schütz kurz anzog und anschließend wieder abfiel, obwohl die logische Freigabe aktiv war.

Die robuste Lösung ist deshalb die Trennung der Verantwortlichkeiten:

- **QC45 Originalsteuerung:** AC-Freigabe und feste AC22-Obergrenze.
- **SGS Integration:** ausschließlich AC-Telemetrie und Diagnose.
- **SGS Integration:** aktive Leistungsregelung nur auf der DC-Seite.

## Netzanschluss

Die fehlende AC-Regelung durch die Integration muss bei der Anlagenplanung berücksichtigt werden. AC22 kann bei dreiphasigem Betrieb bis zu ungefähr 32 A je Phase beanspruchen. Bei einem 35-A-SLS verbleibt damit nur geringe Reserve für weitere Netzlasten.

Die DC-Regelung nach KSEM bleibt aktiv, kann aber eine gleichzeitig auftretende, nicht von ihr steuerbare AC-Last nicht wegregeln. Parallelbetrieb muss deshalb entsprechend der realen Anschluss- und Speicherarchitektur bewertet werden.

## Telemetrie

Die QC45-Firmware liefert die Type-2-Leistung nicht in jeder Situation über ein einzelnes zuverlässiges Feld. Die native Integration enthält deshalb zusätzliche Telemetriepfade, um die tatsächlich aufgenommene AC-Leistung für Anzeige und Diagnose verfügbar zu machen.

Wichtig ist die Trennung:

```text
22 kW = konfigurierte maximale AC-Leistung der Station
11 kW = Beispiel für die tatsächlich gemessene Fahrzeugaufnahme
```

Die reale Leistung ist maßgeblich für Dashboard und evcc.

## Historische Entwicklungsstände

Vor Version 2.0 existierten experimentelle AC-Regelpfade (`AcFixedPowerBridge`, `AcPowerLimitTransport` und weitere Diagnoseansätze). Sie waren wichtig für das Reverse Engineering der ursprünglichen EFACEC-Steuerung, gehören aber **nicht** mehr zur freigegebenen AC-Regelstrategie.

Die Details dieser Versuche bleiben über die Git-Historie nachvollziehbar. Für Betrieb und Wartung ist ausschließlich der oben beschriebene Version-2.0-Stand maßgeblich.

Siehe auch:

- `RELEASE-2.0.md`
- `wiki/Native-Integration.md`
- `native-integration/README.md`
