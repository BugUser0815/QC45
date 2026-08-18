# QC45 Monitor for iOS

Native SwiftUI monitoring/control app for the EFACEC QC45 native integration.

## Security model

- QC45 serves the remote API itself over TLS 1.2
- self-managed server certificate in a JKS keystore
- iOS validates the exact leaf certificate by SHA-256 DER pin
- Bearer token is required in addition to the TLS pin
- the app refuses plain HTTP

## Generate the Xcode project

```bash
cd ios-app
xcodegen generate
open QC45Monitor.xcodeproj
```

## Create the QC45 HTTPS certificate

On the QC45 create a long-lived RSA certificate/keystore. Replace the password before running:

```bash
keytool -genkeypair \
  -alias qc45-api \
  -keyalg RSA \
  -keysize 2048 \
  -sigalg SHA256withRSA \
  -validity 3650 \
  -keystore /home/mobie/evcsd/qc45-api.jks \
  -storepass CHANGE_ME_KEYSTORE_PASSWORD \
  -keypass CHANGE_ME_KEYSTORE_PASSWORD \
  -dname 'CN=dahoam.sgs-elektro.de, OU=QC45, O=SGS Elektrotechnik, C=DE'

chmod 600 /home/mobie/evcsd/qc45-api.jks
```

## Charger configuration

Add to `/home/mobie/evcsd/qc45-integration.properties`:

```properties
remoteapi.enabled=true
remoteapi.bind=0.0.0.0
remoteapi.port=9443
remoteapi.token=PUT_AT_LEAST_32_RANDOM_CHARACTERS_HERE
remoteapi.tls.keyStore=/home/mobie/evcsd/qc45-api.jks
remoteapi.tls.keyStoreType=JKS
remoteapi.tls.keyStorePassword=CHANGE_ME_KEYSTORE_PASSWORD
remoteapi.tls.keyPassword=CHANGE_ME_KEYSTORE_PASSWORD
remoteapi.tls.certificateAlias=qc45-api
```

After restart the integration logs the certificate pin:

```text
[QC45] Remote API certificate SHA-256 pin=0123456789ABCDEF...
```

Copy that exact 64-character value into the iOS app settings.

## Router / public access

NAT rule:

```text
WAN TCP 38443 -> 10.0.0.156 TCP 9443
```

With `dahoam.sgs-elektro.de` pointing to the public WAN address, use this app URL:

```text
https://dahoam.sgs-elektro.de:38443
```

Do not expose the internal OCPP bridge, Tomcat or Modbus ports.

## Test

Local transport test:

```bash
curl -k \
  -H 'Authorization: Bearer PUT_AT_LEAST_32_RANDOM_CHARACTERS_HERE' \
  https://10.0.0.156:9443/api/status
```

Public transport test:

```bash
curl -k \
  -H 'Authorization: Bearer PUT_AT_LEAST_32_RANDOM_CHARACTERS_HERE' \
  https://dahoam.sgs-elektro.de:38443/api/status
```

The iOS app does not use `-k` semantics: it accepts the connection only when the SHA-256 certificate pin exactly matches.

## Features

- QC45 online status and total power
- OCPP 1.6 backend connection state
- KOSTAL KSEM L1/L2/L3 currents
- meter-pause and hard failback state
- CHAdeMO, CCS and Type 2 power/limit/idTag
- Remote Start and Stop
- automatic refresh every 3 seconds
- API token stored in iOS Keychain

Available endpoints:

- `GET /api/health`
- `GET /api/status`
- `POST /api/start?connector=2&idTag=...`
- `POST /api/stop?connector=2`
