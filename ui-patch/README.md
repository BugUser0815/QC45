# QC45 Oberfläche im reduzierten Schnelllader-Design

Dieser Patch ersetzt die operativen Ansichten der vorhandenen EVCSD-UI-JAR
durch eine einheitliche Oberfläche für das 640×480-Display der QC45. Die
proprietäre Basis-JAR und ihre Zustandssteuerung bleiben erhalten.

## Gestaltungsprinzipien

- dunkler, ruhiger Hintergrund mit klarer Informationshierarchie
- Weiß und Grau für Inhalte; Gelb nur für Auswahl, Status und Fortschritt
- einheitliche Kopfzeile mit Gerätestatus und lokaler Uhrzeit
- vier feste Softkey-Felder passend zu den physischen QC45-Gerätetasten
- freigestellte Produktbilder für CCS2, CHAdeMO und Type 2 statt gezeichneter Stecker-Symbole
- Bereitschaftsseite mit dem originalen SGS-Elektrotechnik-Logo samt Schriftzug `Alexander & Marion Rothner`
- keine Animationen, Verläufe, Rundinstrumente oder dekorativen Karten
- feste Aktionsflächen an den ursprünglichen Bedienpositionen
- normaler Ladebildschirm ohne lokale Stop-/Fortsetzen-Tasten
- RFID-gestartete Sessions bleiben dauerhaft auf der vollständigen Ladeübersicht
- Beenden-Hinweis im normalen Ladebildschirm: `Zum Beenden Karte vorhalten oder App benutzen.`
- RemoteStart-Sessions bleiben ebenfalls auf der vollständigen Ladeübersicht und zeigen
  `Zum Beenden App benutzen.`

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
| Bereitschaft | keine Funktion | keine Funktion | keine Funktion | keine Funktion |
| Aktiver Ladevorgang, auch RFID | keine lokale Ladefunktion | keine lokale Ladefunktion | keine lokale Ladefunktion | keine lokale Ladefunktion |

Nicht belegte Tasten werden nicht als aktive Funktion dargestellt. In der
Bereitschaftsansicht werden deshalb weder Pfeile noch Softkey-Hinweise angezeigt.
Während des normalen Ladebildschirms bleiben alle vier Gerätetasten ohne Stop-/
Fortsetzen-Beschriftung; die Session wird entsprechend dem angezeigten Hinweis
per Karte oder App beendet.

Auch wenn EVCSD während einer per RFID gestarteten laufenden Session die Klasse
`WaitingForCardChargingTimer` direkt öffnet, rendert sie weiterhin den vollständigen
AC/DC-Lademonitor. Die frühere dauerhafte Ansicht `KARTE ERKANNT` mit rotem
`LADEVORGANG ABBRECHEN`-Softkey wird nicht mehr eingeblendet. Damit bleibt die
Übersicht mit Ladeleistung, Freigabe, Fahrzeug-SoC, Energie, Ladezeit und
Pufferbatterie während des gesamten Ladevorgangs sichtbar.

Bei einer über OCPP gestarteten Session bleibt dieselbe Ladeübersicht sichtbar.
Das RemoteStart-Flag aus dem nativen Telemetrieblock beeinflusst nur den Hinweis
in der Fußzeile: RemoteStart zeigt `Zum Beenden App benutzen.`, lokale bzw.
RFID-gestartete Sessions zeigen `Zum Beenden Karte vorhalten oder App benutzen.`

`MainForm` und dessen Weiterleitung der Tastencodes an EVCSD werden nicht
verändert. Der Patch ersetzt nur die visuelle Zuordnung und Beschriftung der
bestehenden Zustände.

## Datenquellen des Ladebildschirms

Die Ladeanzeige liest einmal pro Sekunde den versionierten lokalen
Load-Balancing-Block `126–145` auf `127.0.0.1:1502`. Der Bildschirm zeigt AC
und den aktiven DC-Ausgang gleichzeitig:

- gemessene Leistung (`IST`)
- dauerhafte evcc-Anforderung
- netzsichere LoadManager-Zuteilung (`NETZ`)
- Schutzkappe und tatsächlich wirksame `FREIGABE`
- aktive Sessions, Bedarfstransfer sowie Start-, KSEM-, Failback- und Konfigurationssperren
- DC-Fahrzeug-SoC, AC/DC-Sessionenergie und Ladezeiten

`FREIGABE` ist damit sichtbar das Minimum aus evcc-Wunsch, LoadManager-Zuteilung
und GridFailback. Beim gleichzeitigen Laden erklärt die Fußzeile, ob 50/50
geteilt oder ungenutzte Leistung bedarfsgerecht umverteilt wird. Ein
Sicherheitszustand wird rot und mit seiner konkreten Ursache dargestellt.

Falls die installierte native Integrations-JAR den neuen Block noch nicht
bereitstellt, fällt die UI automatisch auf den bisherigen DC-Block `120–125`
zurück. Dadurch kann das UI-Overlay gefahrlos vor der Integrations-JAR
aktualisiert werden.

Der Status in der Kopfzeile folgt dabei bewusst der tatsächlich gemessenen
Ladeleistung und nicht nur dem EVCSD-Sitzungszustand. Sie unterscheidet
`AC LÄDT`, `DC LÄDT`, `AC + DC LÄDT`, `LADEBEREIT`, `KSEM WARTET`,
`NETZSCHUTZ`, `KONFIGURATION` und `SICHERER START`.

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
in eine Kopie der Basis-JAR eingesetzt. Ressourcen unter `src/main/resources`,
insbesondere das SGS-Logo der Bereitschaftsseite, werden ebenfalls in die JAR
übernommen und beim Build geprüft. Für das automatische, abgesicherte Deployment
siehe [`deploy/qc45-ui`](../deploy/qc45-ui/README.md).

Ein eigenständiger Headless-Test kompiliert das komplette Overlay gegen
minimal nachgebildete EVCSD-Verträge, prüft Java-7-Bytecode und rendert dreizehn
640×480-Vorschaubilder einschließlich Auswahl, Vorbereitung, AC/DC-Laden,
Failback, RemoteStart und beider Bereitschaftspfade. Das eingebettete SGS-Logo muss sich dabei tatsächlich
decodieren lassen und in beiden Bereitschaftsbildern sichtbar sein:

```bash
./test.sh
```
