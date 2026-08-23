# Lademonitor UI

Die lokale QC45-Ladeanzeige wird durch einen reproduzierbaren Patch der
vorhandenen EVCSD-UI-JAR ergänzt. Der aktuelle Entwurf orientiert sich an der
klaren Informationshierarchie moderner DC-Lader: Ladeleistung und Fahrzeug-SoC
sind die Hauptwerte, Sessiondaten stehen in einer ruhigeren zweiten Ebene.

## Darstellung

Die Oberfläche ist fest auf das QC45-Display mit **640×480 Pixeln** ausgelegt:

- aktuelle Ladeleistung groß links
- Fahrzeug-SoC groß rechts
- DC-Sollleistung direkt unter der Ist-Leistung
- Energie, Ladezeit und Pufferbatterie-SoC in einer zweiten Zeile
- lokale Uhrzeit in der Kopfzeile
- Gelb nur für Ladestatus und Fortschritt
- keine Animationen, Verläufe oder dekorativen Instrumente
- Hinweis zum Beenden: `zum beenden Karte vorhalten oder App benutzen.`

Der rote Bereich ist kein lokaler Stop-Taster. Er erklärt, dass die laufende
OCPP-/ChargePoint-Session mit Karte oder App beendet wird.

## Modbus-Block für den lokalen Lademonitor

Die Oberfläche liest die Ladedaten ausschließlich aus dem lokalen
Modbus-Server der Native Integration auf `127.0.0.1:1502`:

| Register | Inhalt |
|---:|---|
| 120 | aktuelle DC-Leistung [kW] |
| 121 | freigegebenes DC-Limit [kW] |
| 122 | Fahrzeug-/Batterie-SoC [%] |
| 123 | Ladezeit [s] |
| 124/125 | Sessionenergie [Wh] U32 |

Der gesamte Block wird einmal pro Sekunde in einer einzigen FC03-Abfrage
gelesen. Bei einem kurzen Kommunikationsfehler bleiben die letzten gültigen
Werte höchstens fünf Sekunden sichtbar; anschließend erscheinen Platzhalter.

## Pufferbatterie

Der Pufferbatterie-SoC ist kein QC45-Wert und wird deshalb weiterhin über evcc
gelesen. Standard ist `http://10.0.0.179:7070`; die URL kann mit
`-Devcc.url=...` überschrieben werden. Der Wert wird höchstens alle fünf
Sekunden aktualisiert und ist in der Anzeige bewusst nachgeordnet.

## Reproduzierbarer Patch

Der Quellcode liegt unter:

```text
ui-patch/src/main/java/pt/efacec/es/evcsd/ui/WaitingForCardChargingTimer.java
```

Das Buildskript kompiliert Java-7-Bytecode gegen die aktuell eingesetzte
EVCSD-UI-JAR und ersetzt darin nur diese Klasse samt ihrer inneren Klassen:

```bash
cd ui-patch
./build.sh /pfad/zur/aktuellen-evcsdUI.jar
```

Die proprietäre Basis-JAR wird nicht im Repository gespeichert. Das Ergebnis
liegt unter `ui-patch/target/evcsdUI-qc45-clean-charge-screen.jar`.

Siehe auch: [Modbus TCP](Modbus-TCP) und [Build & Installation](Build-und-Installation).
