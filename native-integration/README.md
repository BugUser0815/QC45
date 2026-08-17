# QC45 native integration

Native extension for the EFACEC QC45 EVCSD/Tomcat JVM.

It replaces the external Python OCPP bridge and the JSP-based Modbus control path with one JAR loaded into the existing EVCSD web application.

## Architecture

```text
ChargePoint
   ^
   | OCPP 1.6 JSON / WSS
   |
qc45-integration.jar
   |-- ReflectionQC45 -> live CentralModule / SatelliteModule / Configuration
   |-- OcppClient      -> Boot, Heartbeat, Status, transactions, MeterValues,
   |                      RemoteStartTransaction, RemoteStopTransaction
   `-- ModbusServer    -> evcc power control

Modbus TCP registers used by evcc:
  0   station power [kW]
  1   CHAdeMO power [kW]
  2   CCS power [kW]
  3   Type2 power [kW]
  4   active DC connector (0/1/2)
 10   CHAdeMO limit [kW]
 11   CCS limit [kW]
 12   Type2 limit [kW]
 20   CHAdeMO active
 21   CCS active
 22   Type2 active
 30   remoteStarted
 40   Configuration.maxPower
 41   Configuration.maxPowerAC
100   active DC power [kW]
101   Type2 power [kW]
110   DC budget [kW] R/W
111   AC budget [kW] R/W
```

Only registers 110 and 111 are writable. Fixed AC/DC configuration limits are not modified.

## Build

```bash
cd native-integration
mvn clean package
```

Output:

```text
target/qc45-integration-0.1.0.jar
```

The project targets Java 7 and has no runtime dependencies outside the servlet API already provided by Tomcat.

## Install on QC45

1. Back up the existing EVCSD installation.
2. Copy the JAR:

```bash
cp target/qc45-integration-0.1.0.jar \
  /home/mobie/evcsd/webapps/ROOT/WEB-INF/lib/qc45-integration.jar
```

3. Create the local configuration (do not commit credentials):

```bash
cp qc45-integration.properties.example \
  /home/mobie/evcsd/qc45-integration.properties
vi /home/mobie/evcsd/qc45-integration.properties
```

4. Add this listener inside the existing `<web-app>` element in `/home/mobie/evcsd/webapps/ROOT/WEB-INF/web.xml`:

```xml
<listener>
  <listener-class>de.rothner.qc45.BootstrapListener</listener-class>
</listener>
```

5. Restart EVCSD/Tomcat.

Expected log lines:

```text
[QC45] native integration started
[QC45] Modbus TCP listening on 1502
[QC45] OCPP connected: wss://...
[QC45] BootNotification: Accepted, heartbeat=...s
```

## OCPP behavior

Implemented:

- BootNotification
- Heartbeat
- StatusNotification
- StartTransaction
- StopTransaction
- MeterValues
- RemoteStartTransaction
- RemoteStopTransaction
- reconnect with exponential backoff
- Basic authentication
- `ocpp1.6` WebSocket subprotocol

Connector mapping:

- 1 = CHAdeMO
- 2 = CCS
- 3 = Type2 AC

Charging state is currently derived from `SatelliteModule.getCurrentPower() > 0`. This is intentionally conservative because the exact internal state enum of this firmware has not yet been mapped. It means `Preparing`, `SuspendedEV`, `SuspendedEVSE` and `Finishing` are not emitted yet; the client uses `Available` and `Charging` reliably from known runtime data.

For a locally started transaction the integration tries `SatelliteModule.getUser()` and then the private `user` field. If neither exists/contains data, `ocpp.defaultIdTag` is used.

## Important runtime assumptions still to verify on the physical charger

- the concrete `NmsListenerImpl` instance is reachable from `CentralModule`, or via a static zero-argument getter;
- `SatelliteModule.stopCharging()` is the correct remote-stop path for all three connector types;
- `getEnergy()` is expressed in Wh (the original firmware API name is known, but its unit should be confirmed against live values);
- the station JVM trusts the ChargePoint TLS certificate chain.

These are isolated in the reflection adapter so firmware-specific adjustments do not affect the OCPP or Modbus layers.
