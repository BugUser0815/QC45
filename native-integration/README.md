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
   |-- ChargingLimitCoordinator -> only writer for all AC/DC power limits
   |-- ChargingLimitGuard       -> fail-closed startup/reconciliation watchdog
   |-- OcppBridgeClient -> OCPP 1.6 backend plus persisted transaction mapping
   |-- Ocpp15BridgeServer -> local EVCSD OCPP 1.5 SOAP translation
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
110   persistent evcc DC request/cap [kW] R/W
111   persistent evcc AC request/cap [kW] R/W
120   active DC charging power [kW]
121   active DC target/limit [kW]
122   vehicle SoC [%]
123   charging time [s]
124   session energy high word [Wh]
125   session energy low word [Wh]
126   AC/DC UI schema version (=1)
127   AC/DC session, flow and safety flags
128   active DC connector (0/1/2)
129-137 DC actual/request/grid/cap/effective/SoC/time/energy
138-145 AC actual/request/grid/cap/effective/time/energy
```

Only registers 110 and 111 are writable. Values below the configured technical
minimum are normalized to 0 kW. evcc requests never write EVCSD directly; the
effective connector limit is always the minimum of evcc request, grid-safe
LoadManager allocation and GridFailback cap/block.

After a JVM/webapp start both outputs use their configured maximum as an
autonomous request cap. Startup, KSEM and failback blockers still keep the
hardware at 0 kW until the LoadManager has prepared a grid-safe target. The
first evcc write takes control of only the addressed output; an explicit 0 kW
then remains a persistent pause for that output.

Modbus access is restricted by `modbus.allowedClients` (exact IP addresses or
CIDR networks); loopback is always permitted. Multi-register writes of 110/111
are applied atomically and all reductions are written before any increase.

The local charging screen prefers the coherent, versioned AC/DC block 126-145
and falls back to the legacy DC block 120-125. It can therefore display actual,
evcc-requested, grid-allocated and effective power for AC and DC at the same
time, including failback, invalid-safety-configuration and demand-transfer
state. The implementation is tied
to fields and methods verified against the original QC45 EVCSD firmware:
`SatelliteInfo.power`, `voltage`, `electricCurrent`, `battEnergyPct`,
`chargingTime`, `energy`, `initialEnergy`, plus
`SatelliteModule.getActiveTransaction()`, `getCurrentEnergy()` and
`getStartTime()`.

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

The project targets the Java 7 API and has no runtime dependencies outside the
servlet API already provided by Tomcat. Allocator, demand tracking, central
limit coordination, 32-bit KSEM decoding and OCPP meter translation are covered
by unit tests during the Maven build.

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
[QC45] native integration started safety=fail-closed AC+DC coordinator=active
[QC45] power requests DC=AUTO 50kW AC=AUTO 22kW; first Modbus write takes control of that channel
[QC45] Modbus TCP listening on 0.0.0.0:1502 ...
[QC45] OCPP bridge connected: wss://...
[QC45] OCPP15 SOAP RX op=bootNotification ...
```

At process/webapp start all three connectors are first forced to 0 kW. Charging
can be released only after five valid KSEM reads and a freshly calculated
grid-safe target. evcc is optional until it explicitly writes a channel budget.
Missing/invalid configuration starts a persistent degraded safe mode which
continues to reassert 0 kW.

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

All OCPP 1.5 meter groups and sampled values are forwarded. Active transaction
to connector mappings are persisted so `RemoteStopTransaction` still resolves
after a JVM/webapp restart. Fragmented backend WebSocket messages are reassembled.

## Grid and charging safety

- Connector 1 is CHAdeMO, connector 2 CCS and connector 3 Type 2 AC.
- One DC connector and Type 2 may charge simultaneously with equal base priority.
- Stably unused entitlement is transferred symmetrically while retaining a 2 kW probe reserve.
- AC is projected conservatively as a possible single-phase 230 V load; delayed
  vehicle ramps and demand transfers are checked against the 34 A command ceiling.
- KSEM currents use the complete 32-bit value, including readings above 65.535 A.
- At 34 A the failback applies its configured reduction; at 35 A it immediately
  blocks AC/DC and hard-trips after 250 ms continuous excess; 38 A trips immediately.
- KSEM failure immediately blocks AC/DC at 0 kW while transactions remain alive.
- A hard trip retries RemoteStop until sessions end and is latched by default.
  Reset requires an E-STOP press/release plus five safe KSEM readings unless the
  explicitly opt-in timed reset is configured.

Connector mapping:

- 1 = CHAdeMO
- 2 = CCS
- 3 = Type2 AC

Charging status combines active-transaction/session evidence, actual power and
the effective connector limit. This allows the bridge to distinguish
`Charging`, `SuspendedEV`, `SuspendedEVSE` and `Finishing` without inventing a
firmware state enum.

## Important physical verification after installation

- verify in a CCS raw trace that a 0-kW V3 START frame is transmitted and acted
  upon by the vehicle;
- test RemoteStop on CHAdeMO, CCS and Type2 through `NmsListenerImpl.abortCharge()`;
- confirm the station JVM trusts the configured ChargePoint TLS certificate chain;
- perform an AC/DC parallel-load test while observing all three KSEM phases and
  the upstream 35-A hardware protection.

These are isolated in the reflection adapter so firmware-specific adjustments do not affect the OCPP or Modbus layers.
