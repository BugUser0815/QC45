# Konfiguration & Betrieb

Produktive Datei:

```text
/home/mobie/evcsd/qc45-integration.properties
```

Vorlage im Repo: `native-integration/qc45-integration.properties.example`.

## OCPP

```properties
ocpp.url=wss://...
ocpp.username=...
ocpp.password=...
ocpp.tls.caFile=/home/mobie/evcsd/ocpp-ca.pem
ocpp.tls.insecure=false
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
modbus.port=1502
```

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
```

Die erweiterten Reboot-Schwellen sind derzeit Code-Defaults: 1000 ms Severe Lag, 3 Samples, 30 s Idle.

## KSEM

```properties
ksem.host=10.0.0.70
ksem.port=502
ksem.unit=71
ksem.timeoutMs=1000
ksem.legacyLowWord=true
ksem.currentScale=0.001
```

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
```

DC und AC nutzen ein gemeinsames Budget. Bei parallelem Laden werden sie bis zum AC-Maximum gleich begrenzt. `gridLimitA` ist die harte konfigurierte Obergrenze; bei aktivem Failback wird für neue Freigaben zusätzlich das niedrigere `failback.reduceA` verwendet.

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
failback.intervalMs=200
failback.resetDelayMs=60000
failback.tripOnMeterFailure=true
failback.meterFailureMs=3000
```

Ab `tripA` werden DC und AC sofort auf 0 kW pausiert. Erst wenn die Überschreitung `tripDelayMs` lang bestehen bleibt, werden alle Connectoren gestoppt und der Hard-Trip-Latch gesetzt.

## Logs

Hauptlog der Erweiterung:

```text
/home/mobie/evcsd/qc45-integration.log
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
