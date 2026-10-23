# CCS Raw Tracing

`CcsRawTracerV2` hängt sich direkt in die RXTX-/Serial-Streams des laufenden EVCSD ein. Ziel ist ein Nachweis dessen, was **wirklich auf dem seriellen Pfad** geschrieben bzw. gelesen wird – nicht nur dessen, was Java-Objekte intern anzeigen.

## Installation

Der Bootstrap startet einen eigenen Daemon, der jede Sekunde nach den vorhandenen seriellen Datenströmen sucht. Ein noch nicht gestartetes `CentralModule`, ein ungeöffneter Port oder nur eine verfügbare Richtung führen deshalb nicht mehr zu einem dauerhaft fehlenden Mitschnitt. Der Daemon blockiert den EVCSD-Start nicht.

TX und RX werden unabhängig eingehängt. Der Daemon erkennt später ersetzte Streams und hängt sie erneut ein, ohne bereits installierte Wrapper zu stapeln. Beim Beenden wird er gestoppt; nur eigene Wrapper werden entfernt, zwischenzeitlich von EVCSD ersetzte Streams bleiben erhalten.

Die Auswahl bevorzugt den Reader der konfigurierten Master-Instanz. Ist diese noch unbenutzt, kann ein aktiver Reader seine tatsächliche Master-Instanz liefern, beispielsweise auf `/dev/ttyACM0` statt `/dev/ttyS0`. Dafür muss sein Besitzer einen `MasterProtoSerializer` verwenden. Ein beliebiger USB-/AC-Reader oder mehrere nicht eindeutig zuordenbare Master sind kein zulässiger Ersatz. Bei fehlender Besitzerinformation ist eine eindeutige Übereinstimmung mit dem konfigurierten Port erforderlich.

Die RX-Seite wird unabhängig vom Diagnose-Logging eingehängt, weil daraus zusätzlich Live-Telemetrie gewonnen wird. Der Property-Schalter bestimmt nur, ob Raw-Zeilen geschrieben werden.

```properties
evcsd.ccsRawTrace.enabled=true
evcsd.ccsRawTrace.repeatMs=1000
```

### Bereitschaft prüfen

Ein unvollständiger Anschluss wird ausdrücklich als `waiting` protokolliert, beispielsweise:

```text
[QC45] CCS-RAW2 waiting txStreams=1 rxStreams=0 ...
```

Bei unverändertem Wartezustand folgt höchstens alle 30 Sekunden eine Erinnerung. Erst wenn beide Wrapper tatsächlich vorhanden sind, erscheint:

```text
[QC45] CCS-RAW2 installed txStreams=1 rxStreams=1 port=... serializer=...
```

Das bestätigt den Anschluss des Tracers, noch keinen Datenverkehr. Vor einem auswertbaren CCS-Test müssen zusätzlich echte `CCS-RAW2 TX ... raw=...`- und `CCS-RAW2 RX ... raw=...`-Zeilen vorliegen. Die angezeigte Schnittstelle wird aus dem gefundenen Objekt übernommen; der Tracer öffnet selbst keinen Port und sendet keine Start-/Stop-Befehle.

## TX-Nachweis

Eine Logzeile wie:

```text
[QC45] CCS-RAW2 TX ... raw=63 02 0A ...
```

belegt, dass ein V3-START mit `0x0A = 10 kW` an den tatsächlichen OutputStream übergeben wurde.

Das ist für die Kona-Analyse entscheidend: Erst ein frischer Trace des **korrigierten** Branches kann beweisen, dass z. B. bei 5 kW wirklich `... 05 ...` physisch in den Efacec-QuickCharge-Pfad gelangt.

## RX-Live-Telemetrie

Für plausible eingehende `0x63`-Statusdaten werden aktuell interpretiert:

- Flags
- SoC
- Spannung
- Strom
- Ladeaktivität

Die Plausibilitätsprüfung verwirft offensichtliche zufällige `0x63`-Treffer in übergeordneten Frames, z. B. SoC > 100 oder unplausible Spannung.

Aus Spannung × Strom wird eine Live-Leistung berechnet. Zusätzlich integriert der Tracer eine Sessionenergie als Fallback, vermeidet dabei aber künstliche Energie über lange Datenlücken.

## Logging-Drosselung

Identische Rohdaten werden nur alle `repeatMs` erneut ausgegeben. Das reduziert Logvolumen, ohne relevante Änderungen zu verlieren.

## Was der Tracer **nicht** sieht

Er sieht die proprietäre serielle Efacec-Strecke. Er dekodiert **nicht** direkt die PLC-/EXI-Nachrichten zwischen CCS-Controller und Fahrzeug. `EVSEMaximumCurrentLimit` im DIN-/ISO-Dialog kann damit nicht direkt bewiesen werden.

Quellcode: `https://github.com/BugUser0815/QC45/blob/native-integration/native-integration/src/main/java/de/rothner/qc45/CcsRawTracerV2.java`

