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

## Bedienung über die vier QC45-Tasten

Die Felder sind an die festen Tastenpositionen oben links, oben rechts, unten
links und unten rechts gebunden:

| Zustand | oben links | oben rechts | unten links | unten rechts |
|---|---|---|---|---|
| Anschlussauswahl | CCS | CHAdeMO | AC | Einstellungen |
| AC/CHAdeMO verbinden | Abbrechen | Start | unbelegt | unbelegt |
| CCS verbinden/autorisieren | Abbrechen | unbelegt | unbelegt | unbelegt |
| Einstellungen oder Sprache | Bestätigen | Nach oben | Zurück | Nach unten |
| Diagnose | unbelegt | unbelegt | Zurück | unbelegt |
| Bereitschaftsanzeige | beliebige Taste öffnet die Anschlussauswahl | wie links | wie links | wie links |
| Laufende Ladung | keine lokale Funktion | keine lokale Funktion | keine lokale Funktion | keine lokale Funktion |

Unbelegte Tasten erhalten keine aktive Fläche. Die laufende Ladung zeigt
bewusst keine Stop- oder Fortsetzen-Funktion an. Die Session wird per Karte oder
App beendet.

Die Tastenerfassung selbst verbleibt unverändert in der originalen EVCSD-UI:
`MainForm` übergibt die Tastencodes weiterhin an die bestehende
Zustandsmaschine. Der Patch ändert ausschließlich Darstellung und Beschriftung.

## Aktiver Ladevorgang und Load Balancing

Die Hauptansicht stellt AC und den aktiven DC-Ausgang gleichberechtigt
nebeneinander dar. Für beide Ladearten werden angezeigt:

| Anzeige | Bedeutung |
|---|---|
| `IST` | tatsächlich gemessene Fahrzeugleistung |
| `EVCC` | aktuell geltende evcc-Obergrenze |
| `NETZ` | vom LoadManager netzsicher berechnete Zuteilung |
| `FREIGABE` | tatsächlich wirksames Minimum einschließlich Failback |

Zusätzlich bleiben DC-Fahrzeug-SoC, AC/DC-Gesamtleistung, gemeinsame
Sessionenergie, ladeartspezifische Zeit und Pufferbatterie-SoC sichtbar. Die
Fußzeile unterscheidet faire gemeinsame Zuteilung, bedarfsgerechte Umverteilung
und Sicherheitszustände. Der rote Bereich ist kein lokaler Stop-Taster, sondern
trägt ausschließlich den Hinweis `Zum Beenden Karte vorhalten oder App benutzen.`
Bei einer ungültigen Sicherheitskonfiguration zeigt die Kopfzeile
`KONFIGURATION`; AC und DC bleiben dabei sichtbar auf 0 kW begrenzt, während
die OCPP-Kommunikation weiterlaufen kann.

Die QC45-Werte stammen aus einer FC03-Abfrage des lokalen Modbus-Servers auf
`127.0.0.1:1502`:

| Register | Inhalt |
|---:|---|
| 126 | Schema-Version (`1`) |
| 127 | Session-/Leistungs-/Schutzstatus als Bitfeld |
| 128 | aktiver DC-Connector (`0/1/2`) |
| 129–137 | DC Ist, evcc, Netz, Schutzkappe, Freigabe, SoC, Zeit, Energie |
| 138–145 | AC Ist, evcc, Netz, Schutzkappe, Freigabe, Zeit, Energie |

Eine ältere native Integrations-JAR wird automatisch erkannt. In diesem Fall
verwendet die Oberfläche weiterhin den kompatiblen DC-Block `120–125`, bis die
neue Integrations-JAR installiert wurde.

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

`ui-patch/test.sh` baut zusätzlich ein Java-7-Testoverlay und rendert Auswahl,
Parallelauswahl, `AC + DC bedarfsgerecht`, `GridFailback` sowie die
Konfigurationssperre als echte 640×480-PNG-Dateien. Dieser Test läuft auch in
GitHub Actions.
