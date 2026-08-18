# QC45 Monitor for iOS

Native SwiftUI monitoring/control app for the EFACEC QC45 native integration.

## Features

- QC45 online status and total power
- OCPP 1.6 backend connection state
- KOSTAL KSEM L1/L2/L3 currents
- meter-pause and hard failback state
- CHAdeMO, CCS and Type 2 power/limit/idTag
- Remote Start with connector + idTag
- Remote Stop
- pull-to-refresh plus automatic refresh every 3 seconds
- API token stored in iOS Keychain

## Generate the Xcode project

Install XcodeGen on the Mac, then:

```bash
cd ios-app
xcodegen generate
open QC45Monitor.xcodeproj
```

Set your Apple Development Team in Signing & Capabilities and run on the iPhone.

## Charger configuration

Add to `/home/mobie/evcsd/qc45-integration.properties`:

```properties
remoteapi.enabled=true
remoteapi.bind=0.0.0.0
remoteapi.port=9080
remoteapi.token=PUT_A_LONG_RANDOM_TOKEN_HERE
```

Restart EVCSD/integration and test from the LAN:

```bash
curl -H 'Authorization: Bearer PUT_A_LONG_RANDOM_TOKEN_HERE' \
  http://10.0.0.156:9080/api/status
```

In the app settings use:

- URL: `http://10.0.0.156:9080` while on the local network/VPN
- Token: the same `remoteapi.token`

## Security

The embedded REST server is HTTP with Bearer authentication. Do **not** port-forward 9080 directly to the public Internet. For remote use, connect the iPhone through a VPN (recommended) or terminate HTTPS on a reverse proxy and expose only that HTTPS endpoint.

Available endpoints:

- `GET /api/health`
- `GET /api/status`
- `POST /api/start?connector=2&idTag=...`
- `POST /api/stop?connector=2`
