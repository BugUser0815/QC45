# LoadManager

Der native LoadManager regelt **den aktiven DC-Ausgang und Type2/AC gemeinsam** anhand der höchsten KSEM-Phasenstrombelastung. CHAdeMO und CCS bleiben untereinander alternativ; parallel vorgesehen ist ein DC-Fahrzeug plus ein AC-Fahrzeug.

## Standardwerte

| Parameter | Wert |
|---|---:|
| Zielstrom | `32.0 A` |
| hartes konfiguriertes Netzlimit | `35.0 A` |
| wirksame Freigabegrenze mit Failback | `34.0 A` |
| Hysterese | `0.8 A` |
| DC Minimum / Maximum | `5 / 50 kW` |
| AC Minimum / Maximum | `5 / 22 kW` |
| Ramp-up | `2 kW / Regelzyklus` |
| Regelintervall | `1000 ms` |

Die wirksame Freigabegrenze ist das Minimum aus `loadmanager.gridLimitA` und `failback.reduceA`. Dadurch bleibt vor dem 35-A-Netzlimit eine zusätzliche Reserve.

## Gemeinsames Budget und gleiche Priorität

Aus Istleistung und Strom-Headroom wird zuerst **ein Gesamtbudget** ermittelt. Sind DC und AC aktiv, wird dieses Budget bis zum AC-Maximum 50/50 geteilt.

| Gesamtbudget | DC | AC |
|---:|---:|---:|
| 10 kW | 5 kW | 5 kW |
| 20 kW | 10 kW | 10 kW |
| 21 kW | 10 kW | 10 kW + 1 kW Reserve |
| 44 kW | 22 kW | 22 kW |
| 50 kW | 28 kW | 22 kW |

Ein ungerades Kilowatt bleibt als neutrale Reserve, solange beide Ausgänge noch gleich hoch begrenzt werden können. Erst wenn AC sein Hardwaremaximum von 22 kW erreicht, erhält DC den verbleibenden Anteil. Reicht das Budget nicht für beide technischen Mindestwerte, werden **beide** Ausgänge auf 0 kW pausiert; keiner erhält stillschweigend Vorrang.

## Regel- und Schutzprinzip

1. KSEM liefert L1/L2/L3; maßgeblich ist die höchste Phase.
2. `targetA - maxPhaseA` bestimmt den Headroom.
3. Erhöhungen sind auf `rampUpKwPerLoop` begrenzt, Reduktionen erfolgen ohne Rampe.
4. Bereits freigegebene, vom Fahrzeug aber noch nicht abgerufene Leistung wird als mögliche Zusatzlast mitgerechnet.
5. Beim Umschichten wird immer zuerst der bisher höher begrenzte Ausgang reduziert und erst danach der andere erhöht.
6. Ab der wirksamen Freigabegrenze werden alle aktiven Budgets sofort 0 kW.

Für die dreiphasige AC22/DC-Leistungsumrechnung gilt:

```text
1 A ≈ √3 × 400 V / 1000 = 0,69282 kW
```

## Startabsicherung für AC und DC

Die Schutzlogik gegen einen EVCSD-internen Sprung auf ein höheres Limit gilt nun für beide Ladearten:

- alle drei Ausgänge werden im Idle vor einer Session auf ihr 5-kW-Minimum vorgerüstet;
- `commandedDcKw` und `commandedAcKw` sind die autoritativ freigegebenen Limits;
- meldet EVCSD bei AC oder DC mehr als freigegeben, wird der niedrigere Sollwert sofort wiederhergestellt;
- ein neu hinzukommender AC- oder DC-Ladevorgang wird noch im selben Regelzyklus in die faire Gesamtverteilung aufgenommen.

## Zusammenspiel mit GridFailback

Der GridFailback startet vor dem LoadManager. Während Überstrom-, Trip- oder KSEM-Ausfallzuständen blockiert er jede Erhöhung. Der LoadManager setzt dann AC und DC ebenfalls wiederholt auf 0 kW. Erst nach der jeweiligen stabilen Freigabebedingung beginnt das gemeinsame Hochrampen erneut.

Quellcode: `https://github.com/BugUser0815/QC45/blob/native-integration/native-integration/src/main/java/de/rothner/qc45/LoadManager.java`

Siehe auch: [Grid-Failback](Grid-Failback), [KSEM-Anbindung](KSEM-Anbindung).
