# QC45 Lademonitor-UI

Dieser Patch ersetzt ausschließlich den Ladesession-Screensaver
`WaitingForCardChargingTimer` der vorhandenen EVCSD-UI-JAR.

## Darstellung

- 640×480 Pixel, passend zum QC45-Display
- aktuelle Ladeleistung und Fahrzeug-SoC als Hauptwerte
- Soll-Leistung, Energie, Ladezeit und Pufferbatterie-SoC als Nebenwerte
- lokaler Zeitstempel
- Hinweis: `zum beenden Karte vorhalten oder App benutzen.`
- keine Animationen, Verläufe oder dekorativen Grafiken

## Datenquellen

Die Ladeanzeige liest pro Sekunde ausschließlich den dokumentierten lokalen
Modbus-Block auf `127.0.0.1:1502`:

| Register | Inhalt |
|---:|---|
| 120 | aktuelle DC-Leistung in kW |
| 121 | freigegebene DC-Sollleistung in kW |
| 122 | Fahrzeug-SoC in % |
| 123 | Ladezeit in Sekunden |
| 124–125 | Sessionenergie als U32 in Wh |

Nur der Pufferbatterie-SoC kommt weiterhin aus evcc. Standardmäßig wird
`http://10.0.0.179:7070/api/state?jq=.battery.soc` verwendet.

Optionale Java-Systemparameter:

```text
-Dqc45.modbus.host=127.0.0.1
-Dqc45.modbus.port=1502
-Devcc.url=http://10.0.0.179:7070
```

## Build

Benötigt werden `jar` sowie entweder der Eclipse-Compiler `ecj` oder ein
`javac`, das noch Java-7-Bytecode erzeugen kann. Unter OpenJDK 21 wird `ecj`
verwendet. Die aktuell eingesetzte UI-JAR wird als Basis übergeben und bleibt
selbst unverändert:

```bash
cd ui-patch
chmod +x build.sh
./build.sh /pfad/evcsdUI-global-charge-cache-refresh.jar
```

Ergebnis:

```text
target/evcsdUI-qc45-clean-charge-screen.jar
```

Die Klasse wird mit Java-7-Bytecode gebaut, passend zur originalen QC45-UI.
Vor dem Austausch die aktive UI-JAR sichern und die neue Datei unter dem Namen
der bisherigen JAR nach `/home/mobie/evcsd/ui/lib/` kopieren.

Für automatische Builds und ein abgesichertes Deployment mit Backup,
UI-only-Neustart und Rollback steht im Repository
[`deploy/qc45-ui`](../deploy/qc45-ui/README.md) bereit.
