# Reverse Engineering

Ein großer Teil der Integration war nur möglich, weil Original-EVCSD, Datenbank, UI-JARs und Mikrocontroller-Firmware untersucht wurden.

## Untersuchte Komponenten

### `evcsd.jar`

Wichtige Klassen:

- `CentralModule`
- `SatelliteModule`
- `QuickChargeSerializer`
- `MessageStateMachines`
- `Configuration`

Daraus stammen unter anderem die Erkenntnisse über:

- `sendCcsStart()`
- `quickChargeMaxCurrent`
- `satelliteMaxPower`
- CCS-V2/V3-Paketaufbau
- Live-Transaktionen, Leistung und Energie

### Derby-Datenbank

Die Datenbank wurde genutzt, um Stations-/Factory-Informationen und persistente Konfigurationswerte gegenzuprüfen. Dabei zeigte sich unter anderem, dass `QC.protocol.version=3` bereits persistent gesetzt war. Die Runtime-Erzwingung ist damit zusätzliche Absicherung und keine Erfindung einer völlig fremden Betriebsart.

### `mobie-master.bin`

Im ursprünglichen Softwarepaket lag:

```text
evcsd/micro_images/mobie-master.bin
```

Größe: 8.960 Byte.

Die AVR-Struktur passt zu einem **ATmega1280**. Der untersuchte alte Stand zeigt für den QuickCharge-Pfad eine serielle Weiterleitung auf einen separaten USART-Kanal; der Master führt dort keine sichtbare P/U-Umrechnung des V3-Leistungsbytes durch.

> [!WARNING]
> Das gefundene Image ist älter als der aktuell laufende Masterstand. Seine Architektur ist ein starker Hinweis, aber kein vollständiger Beweis für die heutige Firmware.

## Wahrscheinlicher CCS-Controller

Öffentliche Ersatzteilinformationen führen Efacec-Teil **20090007 / A19** als **EVAcharge SE Board**. Damit ist A19 sehr wahrscheinlich der HLC-/Green-PHY-Controller zwischen internem Efacec-QuickCharge-Protokoll und Fahrzeug-PLC.

Final zu bestätigen sind weiterhin:

- Platinenfoto A19
- Teilenummer und Boardrevision
- Firmwarestand
- Verkabelung A5/A19/A21

## Wichtigste Architekturfolgerung

```text
EVCSD / Java
   ↓ proprietäres Efacec QuickCharge V3
EFACEC Master
   ↓ seriell
A19 / CCS Controller
   ↓ DIN 70121 / ISO 15118 über PLC
Fahrzeug
```

Damit ist klar, warum ein korrektes `maxPower` im Java-/Efacec-Pfad noch nicht garantiert, dass das Fahrzeug einen korrekten `EVSEMaximumCurrentLimit` sieht.

Siehe auch: [CCS QuickCharge V3](CCS-QuickCharge-V3), [Hyundai Kona](Hyundai-Kona-CCS-Leistungsbegrenzung).
