# CCS QuickCharge V3

Diese Seite beschreibt das **interne EFACEC-Protokoll zwischen EVCSD und QuickCharge-Hardware**. Es ist nicht mit DIN SPEC 70121 oder ISO 15118 auf der Fahrzeugseite gleichzusetzen.

## Wichtigster Befund

Die Originalklasse `QuickChargeSerializer` aus `evcsd.jar` wurde dekompiliert. Für **CCS V3 START** verwendet sie:

```text
MessageStateMachines.maxPower
```

Das resultierende Paket ist fünf Byte lang:

```text
0x63  <flags>  <maxPower_kW>  <CRC16>
```

Beispiel für 5 kW sinngemäß:

```text
63 02 05 xx xx
```

bzw. abhängig vom Login-/Flagzustand z. B.:

```text
63 82 05 xx xx
```

> [!IMPORTANT]
> **Byte 2 ist Leistung in kW, nicht Strom in A.**

## `sendCcsStart()`

Das Original-EVCSD setzt im `MessageStateMachines`-Objekt zwar sowohl:

- `current = quickChargeMaxCurrent`
- `maxPower = satelliteMaxPower`

Der CCS-V3-Serializer nimmt für START aber **nur `maxPower`**. Deshalb bewirkt eine Änderung von `quickChargeMaxCurrent` allein keine dynamische CCS-Strombegrenzung.

## V2

Der analysierte interne CCS-V2-START enthält keinen vergleichbaren dynamischen Leistungswert. V2 ist daher keine sinnvolle Ausweichlösung für das Load Balancing.

## V3-Enforcer

`CcsProtocolV3Enforcer` setzt die laufenden relevanten EVCSD-Objekte auf `QCProtocolVersion = 3`, unter anderem die QuickCharge-Serializer und den MasterProto-Serializer. Der Bootstrap führt diese Erzwingung vor und nach dem Start der Integration aus.

Die Derby-Datenbank wird dabei **nicht** als Konfigurationsspeicher verändert.

## Korrekte Leistungsänderung

Der aktuelle Pfad ist:

```text
LoadManager / Modbus
      ↓
ReflectionQC45.setConnectorLimitKw()
      ↓
Satellite.setMaxPower(kW)
      ↓
sendCcsStart()
      ↓
V3: 0x63 … <kW> …
```

## Verworfener Ampere-Versuch

Zwischenzeitlich wurde aus `P/U` ein Strom berechnet und dieser Wert in Byte 2 geschrieben. Das war protokollseitig falsch und wurde vollständig zurückgenommen. Solche Logs dürfen nicht als Beweis für den heutigen V3-kW-Pfad verwendet werden.

## Fahrzeugseite

Nach dem EFACEC-QuickCharge-Pfad folgt erst der eigentliche CCS-Controller, der `CurrentDemandRes`, `EVSEMaximumPowerLimit` und `EVSEMaximumCurrentLimit` gegenüber dem Fahrzeug erzeugt. Genau dort liegt der aktuelle Kona-Verdacht.

Quellcode:

- `https://github.com/BugUser0815/QC45/blob/native-integration/native-integration/src/main/java/de/rothner/qc45/CcsProtocolV3Enforcer.java`
- `https://github.com/BugUser0815/QC45/blob/native-integration/native-integration/src/main/java/de/rothner/qc45/ReflectionQC45.java`

Siehe auch: [Hyundai Kona](Hyundai-Kona-CCS-Leistungsbegrenzung), [CCS Raw Tracing](CCS-Raw-Tracing).
