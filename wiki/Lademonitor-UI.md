# QC45 Oberfläche

Die operative EVCSD-Oberfläche ist als einheitliches 640×480-Design umgesetzt.
Sie orientiert sich an der klaren Informationshierarchie moderner DC-Lader,
ohne Markenassets oder eine pixelgenaue Kopie zu verwenden.

## Einheitliches Designsystem

- fast schwarzer Hintergrund, weiße Haupt- und graue Nebeninformationen
- Gelb ausschließlich für aktiven Status, Auswahl und Ladefortschritt
- identische Kopfzeile mit QC45, Statuspunkt und Uhrzeit
- feste Funktionsfelder an den Positionen der vier haptischen Gerätetasten
- rechteckige, eindeutig beschriftete Aktionsflächen
- keine Werbung, Animationen, Verläufe oder dekorativen Instrumente

Das System gilt für Anschlussauswahl, Vorbereitung, Autorisierung, Bereitschaft,
Laden, Abschluss, Einstellungen, Sprachwahl und Diagnose. Die Konstruktoren und
EVCSD-Nachrichtenverträge der Originalklassen bleiben erhalten.

## Aktiver Ladevorgang

Aktuelle Ladeleistung und Fahrzeug-SoC sind die Hauptwerte. DC-Sollleistung,
Energie, Ladezeit und Pufferbatterie-SoC stehen in einer nachgeordneten Ebene.
Der rote Bereich ist kein lokaler Stop-Taster, sondern trägt ausschließlich den
Hinweis `zum beenden Karte vorhalten oder App benutzen.`

Die QC45-Werte stammen aus einer FC03-Abfrage des lokalen Modbus-Servers auf
`127.0.0.1:1502`:

| Register | Inhalt |
|---:|---|
| 120 | aktuelle DC-Leistung [kW] |
| 121 | freigegebenes DC-Limit [kW] |
| 122 | Fahrzeug-SoC [%] |
| 123 | Ladezeit [s] |
| 124/125 | Sessionenergie [Wh] U32 |

Bei Kommunikationsfehlern werden gültige Werte höchstens fünf Sekunden
gehalten. Der Pufferbatterie-SoC wird nachgeordnet über evcc gelesen und
höchstens alle fünf Sekunden aktualisiert.

## Reproduzierbarer Patch

Die Quellen liegen unter
`ui-patch/src/main/java/pt/efacec/es/evcsd/ui/`. `build.sh` kompiliert den
vollständigen Satz als Java-7-Bytecode gegen die aktive EVCSD-UI-JAR und setzt
die Klassen in eine Kopie der JAR ein:

```bash
cd ui-patch
./build.sh /pfad/zur/aktuellen-evcsdUI.jar
```

Die proprietäre Basis-JAR wird nicht im Repository gespeichert. Siehe auch
[Modbus TCP](Modbus-TCP) und [Build & Installation](Build-und-Installation).
