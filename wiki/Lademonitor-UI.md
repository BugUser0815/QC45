# Lademonitor UI

Neben der Backend-Integration wurde die lokale QC45-Anzeige angepasst. Ziel war ausdrücklich **keine Neugestaltung der originalen Ladeanzeige**, sondern eine technische Korrektur und Ergänzung bei möglichst unverändertem Layout.

## Umgesetzte/erarbeitete Punkte

### kW statt kWh

Die Originalanzeige verwendete an einer Stelle `kWh`, obwohl dort die aktuelle Ladeleistung dargestellt werden sollte. Die angepasste UI zeigt:

- Live-Leistung in **kW**
- passende Beschriftung ebenfalls **kW**
- keine unnötigen Nachkommastellen
- ursprüngliche Positionierung/Anmutung beibehalten

### Ist / Soll

Zusätzlich wurde vorgesehen, neben der realen Ladeleistung die aktuell freigegebene bzw. maximal mögliche Leistung des Load Balancers darzustellen, z. B. sinngemäß:

```text
20 / 30 kW
Ist / Soll
```

Der Sollwert liegt bereits intern bzw. über den Modbus-Bridge-Registern vor und muss nicht noch einmal separat vom KSEM gelesen werden.

## Modbus-Block für den lokalen Lademonitor

Dafür stellt die native Integration den Bereich 120–125 bereit:

| Register | Inhalt |
|---:|---|
| 120 | aktuelle DC-Leistung [kW] |
| 121 | freigegebenes DC-Limit [kW] |
| 122 | Fahrzeug-/Batterie-SoC [%] |
| 123 | Ladezeit [s] |
| 124/125 | Sessionenergie [Wh] U32 |

Die Werte werden vorzugsweise aus dem bereits von EVCSD aktualisierten `SatelliteInfo` gelesen. Dadurch entsteht keine zusätzliche Hardware-Abfrage.

## Hinweis zur reduzierten Ladeleistung

Für Screensaver/Hinweistext wurde ein neutraler Hinweis vorgesehen, dass die Ladeleistung aufgrund eines begrenzten Netzanschlusses und der eingesetzten Pufferbatterie zeitweise reduziert sein kann. Es sollte **kein Werbebanner** werden.

## Artefaktstatus

Die UI-Anpassungen wurden an vorhandenen EVCSD-UI-JARs durchgeführt. Die binären Original-/Patch-Artefakte sind derzeit nicht als reproduzierbarer Source-Build im QC45-Repo abgelegt. Der Daten-Unterbau im `ModbusServer` ist dagegen versioniert.

> [!NOTE]
> Für langfristige Reproduzierbarkeit wäre es sinnvoll, die konkreten UI-Patches später als dokumentierten Patch-/Buildprozess ins Repo zu übernehmen.

Siehe auch: [Modbus TCP](Modbus-TCP).
