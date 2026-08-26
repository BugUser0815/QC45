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
   |-- ModbusServer    -> evcc power control
   |-- LoadManager     -> demand-aware, equal-priority shared DC/AC KSEM budget
   `-- GridFailback    -> independent DC/AC grid-limit protection

Modbus TCP registers used by evcc and the local charging screen:
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
120   active DC charging power [kW]
121   active DC target/limit [kW]
122   vehicle SoC [%]
123   charging time [s]
124   session energy high word [Wh]
125   session energy low word [Wh]
```

Only registers 110 and 111 are writable. Fixed AC/DC configuration limits are not modified.

The local charging screen reads registers 120-125 as one block. Session energy is reconstructed as `((reg124 << 16) | reg125)` Wh. The implementation is tied to fields and methods verified against the original QC45 EVCSD firmware: `SatelliteInfo.power`, `voltage`, `electricCurrent`, `battEnergyPct`, `chargingTime`, `energy`, `initialEnergy`, plus `SatelliteModule.getActiveTransaction()`, `getCurrentEnergy()` and `getStartTime()`. If the reported DC power is zero while voltage and current are available, register 120 falls back to `voltage * electricCurrent / 1000`.

For installations with the Iskra DC meter, `initialEnergy` is captured at session start and registers 124/125 expose `energy - initialEnergy`. Without that absolute meter baseline, `initialEnergy` remains zero and the charger-reported session energy is exposed directly.

## Build

```bash
cd native-integration
mvn clean package
```

Output:

```text
target/qc45-integration-0.1.0.jar
```

The project targets Java 7 and has no runtime dependencies outside the servlet API already provided by Tomcat. The pure budget allocator is covered by unit tests during the Maven build.

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

During a DC session, charging-screen diagnostics are emitted at most every ten seconds, for example:

```text
[QC45] Modbus screen telemetry: dc=2 power=...kW rawPower=...kW voltage=...V current=...A limit=...kW soc=...% time=...s energy=...Wh initialEnergy=...Wh sessionEnergy=...Wh score=...
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
- the station JVM trusts the ChargePoint TLS certificate chain.

These are isolated in the reflection adapter so firmware-specific adjustments do not affect the OCPP or Modbus layers.
