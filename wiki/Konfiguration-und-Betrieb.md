# Konfiguration & Betrieb

Produktive Datei:

```text
/home/mobie/evcsd/qc45-integration.properties
```

Vorlage im Repo: `native-integration/qc45-integration.properties.example`.

## OCPP

```properties
ocpp.enabled=true
ocpp.url=wss://...
ocpp.username=...
ocpp.password=...
ocpp.tls.caFile=/home/mobie/evcsd/ocpp-ca.pem
ocpp.tls.insecure=false
ocpp.transactionMapFile=/home/mobie/evcsd/qc45-active-transactions.properties
```

## OCPP-1.5-Loopback

```properties
ocpp15.loopback.enabled=true
ocpp15.loopback.bind=127.0.0.1
ocpp15.loopback.port=9000
ocpp15.loopback.path=/QC45
ocpp15.loopback.heartbeatInterval=60
ocpp15.bridge.timeoutMs=10000
```

## Dashboard / Akkuboost

```properties
dashboard.akkuboost.url=http://10.0.20.131:7070
```

Die UI liest den Akkuboost-SoC über `api/state?jq=.battery.soc`. Der Wert aus
der produktiven Konfigurationsdatei überschreibt den in der UI-JAR enthaltenen
Auslieferungsstandard. Alternativ kann die URL beim UI-Start mit
`-Ddashboard.akkuboost.url=...` überschrieben werden; `-Devcc.url=...` bleibt
für bestehende Installationen kompatibel.

## Modbus

```properties
modbus.enabled=true
modbus.bindAddress=0.0.0.0
modbus.port=1502
modbus.allowedClients=10.0.0.179
modbus.maxClients=8
```

`allowedClients` akzeptiert exakte IP-Adressen und CIDR-Netze. Loopback ist
immer erlaubt; Hostnamen und `*` werden bewusst nicht akzeptiert. Eine leere
Liste erlaubt ausschließlich Loopback.

## CCS-Diagnose

```properties
evcsd.ccsRawTrace.enabled=true
evcsd.ccsRawTrace.repeatMs=1000
```

Die V3-Erzwingung wird bewusst **nicht** über Properties mit Strom- oder Hardware-Metadata-Overrides konfiguriert. `CcsProtocolV3Enforcer` setzt die laufenden Serializer in Memory.

## EVCSD Lag Monitor

```properties
evcsd.lagmonitor.enabled=true
evcsd.lagmonitor.intervalMs=60000
evcsd.lagmonitor.warnMs=250
evcsd.lagmonitor.autoRestart=true
evcsd.lagmonitor.restartLagMs=1000
evcsd.lagmonitor.restartConsecutive=3
evcsd.lagmonitor.idleStableMs=30000
evcsd.lagmonitor.restartCommand=sudo -n /sbin/reboot
```

## KSEM

```properties
ksem.host=10.0.0.70
ksem.port=502
ksem.unit=71
ksem.timeoutMs=1000
ksem.currentScale=0.001
ksem.wordOrder=HIGH_LOW
```

`legacyLowWord` ist entfernt beziehungsweise wird bei Alt-Konfigurationen
ignoriert. Nur die vollständige 32-Bit-Auswertung verhindert einen Überlauf
oberhalb 65,535 A.

## SLS und LoadManager

```properties
sls.nominalA=35.0
sls.characteristic=E
sls.thermalTimeConstantMs=30000
loadmanager.enabled=true
loadmanager.targetA=34.0
loadmanager.gridLimitA=35.0
loadmanager.daytimeTargetA=25.0
loadmanager.hysteresisA=1.5
loadmanager.minDcKw=5
loadmanager.maxDcKw=35
loadmanager.minAcKw=5
loadmanager.maxAcKw=43
loadmanager.rampUpKwPerLoop=5
loadmanager.intervalMs=1000
loadmanager.demandStableMs=5000
loadmanager.demandReserveKw=2
```

Der normale LoadManager regelt DC anhand der höchsten KSEM-Phase. Type2 ist
im aktuellen Betrieb ungeregelt und erscheint als Standortlast im Messwert.
Die früheren Demand-Parameter bleiben aus Kompatibilitätsgründen lesbar, sind
für diese direkte DC-Regelung ohne Wirkung.

Der Zielstrom ist das Minimum aus `targetA`, Freigabegrenze und Tagesprofil.
Die Freigabegrenze ist das Minimum aus SLS-Nennstrom, `gridLimitA` und bei
aktivem Failback dessen `reduceA`. `daytimeTargetA` gilt Mo–Sa 06:30–18:00 Uhr
Europe/Berlin; außerhalb dieser Zeit gilt keine feste 35-A-Grenze.
`hysteresisA` bildet einen Haltebereich bis höchstens 5 % des aktiven Sollwerts.
Er wird nicht zusätzlich vom Sollwert abgezogen und schafft keine Reserve
für neue Erhöhungen. Ausstehende DC-Leistung wird weiterhin reserviert;
weitere Erhöhungen benötigen zwei bestätigende Leistungsmessungen.

## GridFailback

```properties
failback.enabled=true
failback.reduceA=36.0
failback.reduceDelayMs=500
failback.reduceDcKw=5
failback.reduceAcKw=5
failback.tripA=38.0
failback.tripDelayMs=250
failback.instantTripA=175.0
failback.intervalMs=100
failback.resetDelayMs=60000
```

`reduceA` und `tripA` lösen proportionale Reduktionen aus; `tripA` reagiert
schneller und bewirkt keine sofortige Pause. Reduktionen erfolgen höchstens
einmal pro Sekunde. `reduceDcKw`/`reduceAcKw` sind der untere Endpunkt der
schrittweisen Reduktion (0 oder technisches Minimum). Nach fünf Messungen
unter `min(reduceA, In)` wird die Kappe bei der aktuellen Zuteilung aufgehoben.

`sls.nominalA` bezeichnet den tatsächlich eingebauten SLS (16–100 A);
fehlende Einträge bedeuten weiterhin E35. Nur Charakteristik E wird unterstützt.
Die ohne explizite Einträge berechneten Schwellen sind `In+1 A`, `In+3 A`
und `5 × In`. Explizit gesetzte niedrigere Werte bleiben unverändert wirksam.
Überhöhte Schwellen werden auf höchstens `1,05 × In`, `1,2 × In`
beziehungsweise `5 × In` begrenzt und protokolliert. Danach muss
`0 < reduceA < tripA < instantTripA` gelten. Ein kleinerer konfigurierter
Instant-Grenzwert wird nicht mehr automatisch auf 175 A angehoben.

Die zusätzliche thermische Softwareüberwachung berücksichtigt I²-Belastung
und Abkühlung jeder Phase. `sls.thermalTimeConstantMs` ist auf 1000–30000 ms
begrenzt (Standard 30000); kleinere Werte führen bei anhaltender Überlast zu
früheren Eingriffen. Das Modell ist keine kalibrierte Herstellerkennlinie.
Details und Hager-Quellen: [Grid-Failback](Grid-Failback).

Mess-/Steuerungsfehler sperren sofort und werden erst nach fünf sicheren
Messungen freigegeben. Messlücken zählen nicht als thermische Abkühlung.
Ein Hard Trip erfordert vor dem automatischen Reset zusätzlich thermische
Erholung, mindestens 60 Sekunden durchgehend sichere Messwerte und beendete
Sitzungen. `failback.autoResetHardTrip` ist obsolet.

### Beispiel nach Austausch auf E50

| Parameter | Vorhandener E35 | Beispiel E50 |
|---|---:|---:|
| `sls.nominalA` | 35 | 50 |
| `loadmanager.targetA` | 34 | 49 |
| `loadmanager.gridLimitA` | 35 | 50 |
| `loadmanager.daytimeTargetA` | 25 | 25 (separat wählbar) |
| `failback.reduceA` | 36 | 51 |
| `failback.tripA` | 38 | 53 |
| `failback.instantTripA` | 175 | 250 |

Nur `sls.nominalA` zu ändern hebt explizit eingetragene alte Ziel-/Grenzwerte
nicht an. Für die höhere Freigabe müssen diese Werte gemeinsam angepasst
werden. Die separate DC-Leistungsgrenze bleibt bei höchstens 35 kW.

## Validierung und Safe Mode

Ports, Intervalle, Skalierung, Grenzwertreihenfolge, Min/Max-Leistung und
boolesche Werte werden beim Start validiert. Fehlerhafte Sicherheitsparameter
lassen den Start-Guard im `DEGRADED SAFE MODE` aktiv; OCPP und Diagnose
bleiben erreichbar. Ein deaktivierter LoadManager hebt die Start-Sperre nicht
auf. KSEM-Timeout höchstens eine Sekunde, LoadManager-Zyklus höchstens eine
Sekunde und Aufwärtsrampe höchstens 5 kW/s bleiben verbindlich. Historische
2-kW-Rampen werden wie bisher auf 5 kW migriert. Die Failback-Zeiten werden
auf höchstens 500/250/100 ms für Reduce/Fast Reduce/Abfrage begrenzt.

## Logs

Hauptlog der Erweiterung:

```text
/home/mobie/evcsd/qc45-integration.log
```

Das Log rotiert **im laufenden Betrieb**, bevor die aktive Datei 10 MiB
überschreitet. Drei Sicherungen bleiben erhalten (`.1` ist die neueste,
`.3` die älteste); die älteste wird bei der nächsten Rotation gelöscht.
Aktive Datei und Sicherungen belegen zusammen höchstens **40 MiB**, auch
bei aktiviertem vollständigem RX-Mitschnitt. Dafür sind weder Cronjob noch
regelmäßige Neustarts oder zusätzliche Properties erforderlich.

Beim Start kürzt die Integration übergroße Altdateien aus früheren Versionen
direkt auf ihre letzten 10 MiB. Dabei wird keine große temporäre Kopie
angelegt. Die laufende Rotation schließt die alte Datei vor dem Umbenennen
und öffnet die aktive Datei anschließend neu. Die ursprünglichen
Tomcat-Streams bleiben parallel erhalten und werden beim Webapp-Shutdown
wiederhergestellt; ihre externe Aufbewahrung wird dadurch nicht geändert.

Zum Verfolgen über Dateirotationen hinweg:

```bash
tail -F /home/mobie/evcsd/qc45-integration.log
```

Sinnvolle Suchmuster:

```text
[QC45] LoadManager
[QC45] GRID FAILBACK
[QC45] CCS-RAW2
[QC45] OCPP15
[QC45] EVCSD EXECUTOR LAG
```

> [!WARNING]
> OCPP-Zugangsdaten und private CA-/Client-Dateien nicht ins öffentliche GitHub-Repo committen.
