# KSEM-Anbindung

Der KOSTAL Smart Energy Meter (KSEM) ist die gemeinsame Netz-Messquelle für **LoadManager**, **GridFailback** und den separaten **PeakShaving-`lean`-Prozess**.

## Standardparameter

| Parameter | Wert |
|---|---:|
| Host | `10.0.0.70` |
| Port | `502` |
| Unit ID | `71` |
| Timeout | `1000 ms` im QC45-JAR |
| Strom-Skalierung | `0.001` |
| Wortreihenfolge | `HIGH_LOW` |
| Auswertung | vollständiger unsigned 32-Bit-Wert × `0.001` |

## Phasenstromregister im QC45-JAR

`KsemClient` liest je zwei Holding Registers ab:

- L1: Register `60`
- L2: Register `100`
- L3: Register `140`

Der Regelwert ist immer:

```text
criticalA = max(L1, L2, L3)
```

Damit schützt die Regelung die **am stärksten belastete Phase** und nicht nur die Summenleistung.

Die frühere Low-Word-Auswertung ist entfernt. Sie hätte beispielsweise
`70.000 mA` nach dem 16-Bit-Überlauf fälschlich als `4.464 A` angezeigt. Der
aktuelle Decoder liest beide Wörter und liefert korrekt `70.0 A`; alternativ
kann für abweichende Geräte `LOW_HIGH` konfiguriert werden.

## Bewusst keine Dauerverbindung

Die QC45-Integration öffnet für einen Messzyklus eine TCP-Verbindung, liest die
drei Werte und schließt sie wieder. LoadManager und GridFailback besitzen dabei
bewusst **getrennte `KsemClient`-Instanzen**, damit ein langsamer normaler
Regelzyklus den unabhängigen 100-ms-Failback nicht blockiert.

Das ist absichtlich konservativ, weil mehrere Komponenten auf den KSEM zugreifen können, unter anderem:

- QC45 LoadManager
- QC45 GridFailback
- PeakShaving-Prozess
- evcc bzw. weitere Monitoring-Software

Eine einzelne dauerhaft gehaltene Verbindung bringt für diese Zykluszeiten wenig Vorteil und kann bei einfachen Modbus-Geräten eher Konflikte erzeugen.

## PeakShaving-Leser

Der C++-`lean`-Prozess ist effizienter als der historische Python-Aufbau: Er liest pro Zyklus vier zusammenhängende Modbus-Blöcke statt vieler Einzelrequests. Die alte Register-/Skalierungssemantik wird dabei absichtlich kompatibel nachgebildet.

## Fehlerbehandlung

Im QC45-Failback ist ein KSEM-Ausfall selbst ein Schutzereignis: Schon der erste
ungültige Read setzt AC und DC auf 0 kW, **ohne die Transaktionen zu beenden**.
Nach fünf gültigen Messungen unterhalb der Reduktionsschwelle wird die Pause
wieder freigegeben; die Leistungsziele müssen anschließend neu berechnet werden.

Quellcode QC45: `https://github.com/BugUser0815/QC45/blob/native-integration/native-integration/src/main/java/de/rothner/qc45/KsemClient.java`

Quellcode PeakShaving: `https://github.com/BugUser0815/PeakShaving/blob/lean/src/main.cpp`
