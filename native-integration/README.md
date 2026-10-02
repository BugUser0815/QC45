# QC45 native integration

## Release status

**QC45 Integration Version 2.0** was released on 2 October 2026.

Reference runtime commit before documentation-only follow-up commits:

```text
2487b7b1ea5b21338b6394f12199b1cf0a377020
```

See [`../RELEASE-2.0.md`](../RELEASE-2.0.md) for the release record.

## Purpose

This project adds a native Java extension to the EFACEC QC45 EVCSD/Tomcat JVM. It consolidates OCPP bridging, Modbus/evcc integration, DC load management, grid failback, telemetry and diagnostics inside the existing station process.

Version 2.0 deliberately separates DC control from Type 2 AC operation:

- **DC (CCS / CHAdeMO): actively controlled by the SGS integration.**
- **Type 2 AC: controlled by the original QC45 firmware with a fixed AC22 maximum; SGS is read-only for AC control and only provides telemetry/diagnostics.**

## Architecture

```text
ChargePoint
   ^
   | OCPP
   |
qc45-integration.jar
   |-- ReflectionQC45      -> live EVCSD objects
   |-- OcppBridgeClient    -> OCPP 1.6 backend
   |-- Ocpp15BridgeServer  -> local OCPP 1.5 SOAP translation
   |-- ModbusServer        -> evcc/UI interface
   |-- KsemClient          -> grid measurements
   |-- LoadManager         -> DC power allocation
   |-- GridFailback        -> DC grid protection
   |-- ChargingLimitGuard  -> DC limit supervision
   |-- AcPowerTelemetry    -> read-only Type 2 telemetry
   `-- SafetyDiagnostics   -> station diagnostics

Type 2 AC control path:
QC45 firmware -> fixed AC22 maximum -> vehicle
                |
                +-> telemetry -> integration -> UI / Modbus / evcc / logs
```

## Type 2 AC in Version 2.0

The station is operated with an original QC45 AC setting of **AC22 / 22 kW maximum**.

The SGS integration does **not**:

- write Type 2 power limits,
- dynamically control the Type 2 pilot current,
- use KSEM headroom to change AC power,
- use evcc requests to change AC power,
- re-enable the experimental native AC load-balancing paths.

The integration still reads session state, actual power and energy for display and diagnostics.

A production acceptance test on 2 October 2026 with a Tesla Model Y showed approximately **11 kW actual Type 2 power** consistently on both the QC45 display and evcc while charging remained stable. This confirms that the telemetry represents the vehicle's actual draw instead of merely showing the configured 22 kW ceiling.

See [`../wiki/Type2-AC-Leistungsbegrenzung.md`](../wiki/Type2-AC-Leistungsbegrenzung.md).

## DC control in Version 2.0

DC remains actively managed by the integration. The released system includes:

- KSEM grid measurements,
- direct KSEM-based DC allocation,
- dynamic DC pre-arm,
- 5 kW Notladen/fallback,
- grid failback and hard-trip protection,
- CCS and CHAdeMO telemetry,
- Modbus/evcc integration,
- OCPP backend integration.

The Type 2 read-only decision does not disable or weaken the DC control path.

## Modbus TCP

The integration exposes station and connector telemetry to evcc and the local charging screen. The exact register map remains defined in `ModbusServer` and its tests.

Operational rule for Version 2.0:

- DC control registers may influence DC charging power.
- AC values are telemetry/diagnostic values and must not be interpreted as an SGS-controlled physical Type 2 limit.

Access is restricted through `modbus.allowedClients`; loopback remains permitted.

## Build

```bash
cd native-integration
mvn clean package
```

The existing Maven artifact name may still contain an older internal package version. The operational release designation **Version 2.0** refers to the released integrated system and Git state, not the Maven filename.

The project uses the Eclipse compiler under OpenJDK 21 to emit Java 7 bytecode and is designed for the original QC45 Tomcat/EVCSD runtime.

## Install on QC45

Back up the existing EVCSD installation before changing the station.

For repeatable deployments use:

```text
deploy/qc45-integration
```

The deployment path builds/tests the JAR, installs it into the existing `smartgrid` webapp and restarts the complete QC45 so EVCSD, Tomcat and the native integration start from a consistent state.

Typical persistent integration log:

```text
/home/mobie/evcsd/qc45-integration.log
```

## OCPP behavior

The native bridge supports the station functions required by the current installation, including:

- BootNotification,
- Heartbeat,
- StatusNotification,
- StartTransaction,
- StopTransaction,
- MeterValues,
- RemoteStartTransaction,
- RemoteStopTransaction,
- reconnect handling,
- backend authentication/TLS as configured.

Active transaction mappings are persisted so remote-stop handling survives JVM/webapp restarts.

## Safety and operating rules

### DC

DC power increases are subject to the current grid-safe allocation and protection logic. The 5 kW Notladen/fallback remains the defined degraded operating floor where applicable. Hard-trip behavior remains independent of normal ramp control.

### Type 2 AC

The SGS integration must not be considered a Type 2 grid-protection actuator in Version 2.0. AC22 can consume roughly 32 A per phase at full three-phase output, leaving little margin on a 35 A upstream SLS. Parallel AC/DC operation therefore has to be assessed against the actual installation and battery/grid architecture.

Do not re-enable historical `AcFixedPowerBridge`, `AcPowerLimitTransport` or similar experimental AC limit paths as part of an unrelated change. Any renewed active AC-control work requires a separate test branch and real vehicle validation before release.

## Release workflow

Development and real-station validation take place on `native-integration`. A tested release can then be fast-forwarded to `main`.

Version 2.0 was promoted this way: the pre-release branch was 162 commits ahead of the old `main`, zero commits behind, and `main` was fast-forwarded without force-push.

## Further documentation

- [`../RELEASE-2.0.md`](../RELEASE-2.0.md)
- [`../wiki/Native-Integration.md`](../wiki/Native-Integration.md)
- [`../wiki/Type2-AC-Leistungsbegrenzung.md`](../wiki/Type2-AC-Leistungsbegrenzung.md)
- [`../wiki/Modbus-TCP.md`](../wiki/Modbus-TCP.md)
- [`../wiki/OCPP-Bridge.md`](../wiki/OCPP-Bridge.md)
