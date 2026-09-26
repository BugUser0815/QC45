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
- RFID-gestartete Sessions zeigen die Ladeübersicht; nach erneuter gültiger Karte erscheint die Abbruchbestätigung
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
| Aktiver Ladevorgang | keine lokale Ladefunktion | keine lokale Ladefunktion | keine lokale Ladefunktion | keine lokale Ladefunktion |
| Gültige Karte während lokaler Ladung | Ladevorgang beenden | – | – | – |

Nicht belegte Tasten werden nicht als aktive Funktion dargestellt. In der
Bereitschaftsansicht werden deshalb weder Pfeile noch Softkey-Hinweise angezeigt.
Während des normalen Ladebildschirms bleiben alle vier Gerätetasten ohne Stop-/
Fortsetzen-Beschriftung. Nach dem anfänglichen Login läuft der Ladebildschirm normal weiter.
Erst wenn EVCSD abgemeldet hat und die passende RFID-Karte erneut den
`ChargeInfo.loggedIn`-Status setzt, zeigt die UI `KARTE ERKANNT` mit dem
roten Softkey `LADEVORGANG ABBRECHEN` oben links. Das Vorhalten der Karte
allein beendet die Ladung nicht; der Benutzer muss die Taste bestätigen.
Nach EVCSD-Logout oder Timeout erscheint wieder die vollständige Ladeübersicht.

Auch wenn EVCSD während einer laufenden Session die Klasse
`WaitingForCardChargingTimer` direkt öffnet, bleibt ohne das RFID-Statussignal
der AC/DC-Lademonitor sichtbar. Bei einer über OCPP gestarteten Session bleibt
die Ladeübersicht auch bei einem gesetzten EVCSD-Login sichtbar; das frische
RemoteStart-Flag verhindert dort eine lokale Stop-Aufforderung. In diesem Fall
zeigt die Fußzeile `Zum Beenden App benutzen.`, bei einer lokalen Session
`Zum Beenden Karte vorhalten oder App benutzen.`

`MainForm` und dessen Weiterleitung der Tastencodes an EVCSD werden nicht
verändert. Der Patch ersetzt nur die visuelle Zuordnung und Beschriftung der
bestehenden Zustände.

## Datenquellen des Ladebildschirms

Die Ladeanzeige liest einmal pro Sekunde den versionierten lokalen
Telemetrieblock `126–145` auf `127.0.0.1:1502`. Der Bildschirm zeigt AC
und den aktiven DC-Ausgang gleichzeitig. AC wird in der QC45 fest auf 22 kW
eingestellt und von der Integration nicht mehr leistungsbegrenzt. Die AC-Kachel
zeigt die tatsächliche Ladeleistung (`IST`) aus der Live-Telemetrie sowie
`MAXIMUM 22 kW` und `OHNE LASTREGELUNG`. Der dynamische AC-Grenzwert aus dem
Block ist nur noch ein logischer Altwert und erscheint nicht als Freigabe.

Die DC-Kachel zeigt weiterhin:

- gemessene Leistung (`IST`)
- dauerhafte evcc-Anforderung
- netzsichere LoadManager-Zuteilung (`NETZ`)
- Schutzkappe und tatsächlich wirksame `FREIGABE`
- aktive Sessions sowie Start-, KSEM-, Failback- und Konfigurationssperren für DC
- DC-Fahrzeug-SoC, AC/DC-Sessionenergie und Ladezeiten

Die DC-`FREIGABE` ist das Minimum aus evcc-Wunsch, LoadManager-Zuteilung
und GridFailback. Die Fußzeile bezeichnet Sperren ausdrücklich als DC-Zustand;
sie suggeriert keine gemeinsame AC/DC-Lastverteilung. Ein DC-Sicherheitszustand
wird mit seiner konkreten Ursache dargestellt.
Der separate Schutzstatus auf `127.0.0.1:1503` ist fail-visible: Ist die native
Diagnose nicht erreichbar oder inkompatibel, meldet die Fußzeile den fehlenden
Sicherheitsstatus, statt einen Normalzustand vorzutäuschen.

Falls die installierte native Integrations-JAR den neuen Block noch nicht
bereitstellt, fällt die UI automatisch auf den bisherigen DC-Block `120–125`
zurück. Dadurch kann das UI-Overlay gefahrlos vor der Integrations-JAR
aktualisiert werden.

Der Status in der Kopfzeile folgt dabei bewusst der tatsächlich gemessenen
Ladeleistung und nicht nur dem EVCSD-Sitzungszustand. Sie unterscheidet
`AC LÄDT`, `DC LÄDT`, `AC + DC LÄDT`, `LADEBEREIT`, `KSEM WARTET`,
`NETZSCHUTZ`, `KONFIGURATION` und `SICHERER START`.

Nur der Akkuboost-SoC kommt weiterhin aus evcc. Der Endpunkt wird über die
gemeinsam genutzte Datei `/home/mobie/evcsd/qc45-integration.properties`
konfiguriert:

```properties
dashboard.akkuboost.url=http://10.0.20.131:7070
```

Die UI-JAR enthält denselben Wert als Auslieferungsstandard. Dadurch greift die
neue Adresse auch dann, wenn die produktive Datei den Schlüssel noch nicht
enthält. Ein dort gesetzter Wert hat Vorrang.

Optionale Java-Systemparameter:

```text
-Dqc45.modbus.host=127.0.0.1
-Dqc45.modbus.port=1502
-Ddashboard.akkuboost.url=http://10.0.20.131:7070
-Dqc45.integration.config=/home/mobie/evcsd/qc45-integration.properties
```

Der bisherige Parameter `-Devcc.url` bleibt als kompatibler Override erhalten.

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
