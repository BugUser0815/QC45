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

## LoadManager

```properties
loadmanager.enabled=true
loadmanager.targetA=32.0
loadmanager.gridLimitA=35.0
loadmanager.hysteresisA=0.8
loadmanager.minDcKw=5
loadmanager.maxDcKw=50
loadmanager.minAcKw=5
loadmanager.maxAcKw=22
loadmanager.rampUpKwPerLoop=2
loadmanager.intervalMs=1000
loadmanager.demandStableMs=5000
loadmanager.demandReserveKw=2
```

DC und AC nutzen ein gemeinsames Budget. Bei parallelem Laden erhalten sie zunächst denselben Anteil. Wird ein Anteil fünf Sekunden stabil nicht genutzt, kann der andere Ausgang ihn übernehmen; 2 kW Aufwachreserve bleiben am bedarfsgedeckelten Ausgang. `gridLimitA` ist die harte konfigurierte Obergrenze; bei aktivem Failback wird für neue Freigaben zusätzlich das niedrigere `failback.reduceA` verwendet.

## GridFailback

```properties
failback.enabled=true
failback.reduceA=34.0
failback.reduceDelayMs=500
failback.reduceDcKw=5
failback.reduceAcKw=5
failback.tripA=35.0
failback.tripDelayMs=250
failback.instantTripA=38.0
failback.intervalMs=100
failback.autoResetHardTrip=false
failback.resetDelayMs=60000
```

Die drei Stromschwellen müssen strikt aufsteigend sein:
`reduceA < tripA < instantTripA`. Kollidierende Altwerte werden beim Start
ausschließlich nach unten in eine sichere Reihenfolge mit mindestens 0,1 A
Abstand überführt; das Log nennt konfigurierte und wirksame Werte. Ist keine
positive, sichere Reihenfolge möglich, hält die Integration AC und DC auf
0 kW, startet OCPP und die Diagnoseoberfläche aber weiterhin.

Ältere Failback-Zeiten werden ebenfalls ausschließlich verschärft. Insbesondere
wird ein historisches `failback.intervalMs=200` automatisch auf 100 ms
reduziert, statt LoadManager und GridFailback vollständig abzuschalten.

Ab `tripA` werden DC und AC sofort auf 0 kW pausiert. Erst wenn die Überschreitung `tripDelayMs` lang bestehen bleibt, werden alle Connectoren gestoppt und der Hard-Trip-Latch gesetzt.

Ein KSEM-Fehler pausiert sofort. Standardmäßig wird der Hard Trip nur über eine
E-STOP-Betätigung mit anschließendem Loslassen und fünf sicheren Reads
entriegelt. Der zeitgesteuerte Reset wird nur mit
`autoResetHardTrip=true` aktiviert.

## Validierung und Safe Mode

Ports, Intervalle, Skalierung, Grenzwertreihenfolge, Min/Max-Leistung und
boolesche Werte werden beim Start validiert. Fehlerhafte Sicherheitsparameter
starten keinen halb konfigurierten Regler, sondern lassen den bereits aktiven
0-kW-Guard im `DEGRADED SAFE MODE` weiterlaufen. Ein deaktivierter LoadManager
hebt die Start-Sperre ebenfalls nicht auf. Werte, die die feste
32/34/35/38-A-Sicherheitsstaffel, den KSEM-Timeout von höchstens einer Sekunde
oder die maximale Steigerung von 2 kW/s abschwächen würden, werden abgelehnt.

## Logs

Hauptlog der Erweiterung:

```text
/home/mobie/evcsd/qc45-integration.log
```

Das Log rotiert ab 10 MiB beim nächsten Start (`.1` bis `.3`). Die ursprünglichen
Tomcat-Streams bleiben parallel erhalten und werden beim Webapp-Shutdown
wiederhergestellt.

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
