# QC45 Oberfläche im reduzierten Schnelllader-Design

Dieser Patch ersetzt die operativen Ansichten der vorhandenen EVCSD-UI-JAR
durch eine einheitliche, codegerenderte Oberfläche für das 640×480-Display der
QC45. Die proprietäre Basis-JAR und ihre Zustandssteuerung bleiben erhalten.

## Gestaltungsprinzipien

- dunkler, ruhiger Hintergrund mit klarer Informationshierarchie
- Weiß und Grau für Inhalte; Gelb nur für Auswahl, Status und Fortschritt
- einheitliche Kopfzeile mit Gerätestatus und lokaler Uhrzeit
- vier feste Softkey-Felder passend zu den physischen QC45-Gerätetasten
- keine Animationen, Verläufe, Rundinstrumente oder dekorativen Karten
- feste Aktionsflächen an den ursprünglichen Bedienpositionen
- Ladebildschirm ohne lokale Stop-/Fortsetzen-Tasten
- Beenden-Hinweis: `zum beenden Karte vorhalten oder App benutzen.`

## Ersetzte Betriebsansichten

Der Patch umfasst Start, Anschlussauswahl, Mehrfachladung, Vorbereitung für AC,
CCS und CHAdeMO, Autorisierung, Bereitschaft/Kartenleser, aktive AC-/CCS-/CHAdeMO-
Ladung, Warten auf das Fahrzeug, Sitzungsabschluss, Einstellungen, Sprache und
Diagnose. Seltene Firmware-/Wartungshintergründe verbleiben bewusst in der
Original-JAR.

Alle öffentlichen Konstruktoren und `ActionPanel`-Datenverträge der ersetzten
Klassen bleiben kompatibel zur EVCSD-Zustandsmaschine.

## Vier physische Gerätetasten

Die Bedienfelder liegen fest an den Positionen der vier haptischen QC45-Tasten.
Die Zuordnung wird nicht zwischen den Ansichten verschoben:

| Ansicht | oben links | oben rechts | unten links | unten rechts |
|---|---|---|---|---|
| Anschlussauswahl | CCS | CHAdeMO | AC | Einstellungen |
| AC-/CHAdeMO-Vorbereitung | Abbrechen | Start | – | – |
| CCS-Vorbereitung/Autorisierung | Abbrechen | – | – | – |
| Einstellungen/Sprache | Bestätigen | Nach oben | Zurück | Nach unten |
| Diagnose | – | – | Zurück | – |
| Bereitschaft | beliebige Taste öffnet die Anschlussauswahl | wie links | wie links | wie links |
| Aktiver Ladevorgang | keine lokale Ladefunktion | keine lokale Ladefunktion | keine lokale Ladefunktion | keine lokale Ladefunktion |

Nicht belegte Tasten werden nicht als aktive Funktion dargestellt. Während des
Ladens bleiben alle vier Gerätetasten ohne Stop-/Fortsetzen-Beschriftung; die
Session wird entsprechend dem angezeigten Hinweis per Karte oder App beendet.

`MainForm` und dessen Weiterleitung der Tastencodes an EVCSD werden nicht
verändert. Der Patch ersetzt nur die visuelle Zuordnung und Beschriftung.

## Datenquellen des Ladebildschirms

Die Ladeanzeige liest einmal pro Sekunde den dokumentierten lokalen Modbus-Block
auf `127.0.0.1:1502`:

| Register | Inhalt |
|---:|---|
| 120 | aktuelle DC-Leistung in kW |
| 121 | freigegebene DC-Sollleistung in kW |
| 122 | Fahrzeug-SoC in % |
| 123 | Ladezeit in Sekunden |
| 124–125 | Sessionenergie als U32 in Wh |

Der Status in der Kopfzeile folgt dabei bewusst der tatsächlich gemessenen
Ladeleistung und nicht nur dem EVCSD-Sitzungszustand: `LÄDT` wird ausschließlich
bei einem frischen Messwert größer als `0 kW` angezeigt. Bei aktiver bzw.
vorbereiteter Ladesitzung ohne gemessenen Leistungsfluss steht dort
`LADEBEREIT`. Dadurch wird insbesondere während Kommunikation, Autorisierung,
Hochlauf oder einer Ladepause nicht fälschlich ein aktiver Energiefluss
angezeigt.

Nur der Pufferbatterie-SoC kommt weiterhin aus evcc. Standardmäßig wird
`http://10.0.0.179:7070/api/state?jq=.battery.soc` verwendet.

Optionale Java-Systemparameter:

```text
-Dqc45.modbus.host=127.0.0.1
-Dqc45.modbus.port=1502
-Devcc.url=http://10.0.0.179:7070
```

## Build

Benötigt werden `jar` sowie entweder ECJ oder ein `javac`, das Java-7-Bytecode
erzeugen kann. Die aktive UI-JAR wird als Basis übergeben:

```bash
cd ui-patch
chmod +x build.sh
./build.sh /pfad/evcsdUI-v4_EFACEC-ALL_IN_ONE_GENERIC.jar
```

Ergebnis:

```text
target/evcsdUI-qc45-alpitronic-ui.jar
```

Alle Patchklassen werden als Java-7-Bytecode (Class-Major-Version 51) gebaut und
in eine Kopie der Basis-JAR eingesetzt. Für das automatische, abgesicherte
Deployment siehe [`deploy/qc45-ui`](../deploy/qc45-ui/README.md).
