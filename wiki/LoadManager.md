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
| AC Minimum / Maximum | `5 / 43 kW` |
| Ramp-up | `2 kW / Regelzyklus` |
| Regelintervall | `1000 ms` |
| stabile Bedarfserkennung | `5000 ms` |
| Aufwachreserve | `2 kW` |

Die wirksame Freigabegrenze ist das Minimum aus `loadmanager.gridLimitA` und `failback.reduceA`. Dadurch bleibt vor dem 35-A-Netzlimit eine zusätzliche Reserve.

## Gemeinsames Budget und gleiche Priorität

Aus Istleistung und Strom-Headroom wird zuerst **ein Gesamtbudget** ermittelt. Sind DC und AC aktiv, wird dieses Budget bis zum AC-Maximum 50/50 geteilt.

| Gesamtbudget | DC | AC |
|---:|---:|---:|
| 10 kW | 5 kW | 5 kW |
| 20 kW | 10 kW | 10 kW |
| 21 kW | 10 kW | 10 kW + 1 kW Reserve |
| 44 kW | 22 kW | 22 kW |
| 50 kW | 25 kW | 25 kW |
| 86 kW | 43 kW | 43 kW |
| 93 kW | 50 kW | 43 kW |

Ein ungerades Kilowatt bleibt als neutrale Reserve, solange beide Ausgänge noch gleich hoch begrenzt werden können. Erst wenn AC sein Hardwaremaximum von 43 kW erreicht, erhält DC den verbleibenden Anteil. Reicht das Budget nicht für beide technischen Mindestwerte, werden **beide** Ausgänge auf 0 kW pausiert; keiner erhält stillschweigend Vorrang.

## Bedarfsgerechte Umverteilung

50/50 bleibt der faire Anspruch beider Fahrzeuge. Ein Anteil wird erst umverteilt, wenn er mindestens `demandStableMs` lang deutlich nicht abgerufen wird und das andere Fahrzeug seinen fairen Anteil tatsächlich nutzt.

Beispiel bei 30 kW Gesamtbudget:

| Zustand | DC | AC |
|---|---:|---:|
| fairer Anspruch | 15 kW | 15 kW |
| gemessen | 15 kW | 11 kW |
| nach 5 s stabil | 17 kW | 13 kW |

Die 13-kW-AC-Freigabe besteht dabei aus 11 kW gemessenem Bedarf plus 2 kW Aufwachreserve.

Das Gesamtbudget bleibt unverändert bei 30 kW. Nimmt das AC-Fahrzeug anschließend mindestens einen Teil seiner Aufwachreserve auf, wird zuerst DC wieder auf 15 kW reduziert und danach AC auf 15 kW freigegeben. Dasselbe Verfahren funktioniert spiegelbildlich für ungenutzten DC-Anteil zugunsten von AC. Sind beide Fahrzeuge bedarfsgedeckelt oder nutzt der Empfänger seinen fairen Anteil noch nicht, findet keine Umverteilung statt.

Der lokale Lademonitor zeigt diesen Zustand direkt an. Für AC und DC sind
jeweils Istleistung, evcc-Obergrenze, LoadManager-Zuteilung und wirksame
Freigabe sichtbar; eine aktive Umverteilung wird ausdrücklich als
`BEDARFSGERECHT` gekennzeichnet.

## Regel- und Schutzprinzip

1. KSEM liefert L1/L2/L3; maßgeblich ist die höchste Phase.
2. `targetA - maxPhaseA` bestimmt den Headroom.
3. Erhöhungen sind auf `rampUpKwPerLoop` begrenzt, Reduktionen erfolgen ohne Rampe.
4. Bereits freigegebene, vom Fahrzeug aber noch nicht abgerufene Leistung wird als mögliche Zusatzlast mitgerechnet.
5. Neu gemessene Fahrzeugleistung wird erst nach einem weiteren Zyklus als bereits im KSEM-Messwert enthalten gutgeschrieben. Das schließt den Zeitversatz zwischen Stations- und Netz-Messwert.
6. Bedarfstransfer verändert zwar nicht die kW-Summe, wird aber erneut auf den Phasenstrom geprüft, weil eine Verschiebung von DC zu einphasigem AC mehr kritischen Phasenstrom erzeugen kann.
7. Beim Umschichten wird immer zuerst der bisher höher begrenzte Ausgang reduziert und erst danach der andere erhöht.
8. Ab der wirksamen Freigabegrenze werden alle aktiven Budgets sofort 0 kW.

Für das zunächst ermittelte Gesamtbudget gilt die dreiphasige Näherung:

```text
1 A ≈ √3 × 400 V / 1000 = 0,69282 kW
```

Die abschließende Sicherheitsprojektion rechnet DC konservativ mit
`0,60 kW/A`. Type2 wird als möglicherweise **einphasige** Last mit nur
`0,20 kW/A` behandelt. Damit werden auch 207 V und ein nicht idealer
Leistungsfaktor berücksichtigt.

## Startabsicherung für AC und DC

Die Schutzlogik gegen einen EVCSD-internen Sprung auf ein höheres Limit gilt
für beide Ladearten:

- beim JVM-/Webapp-Start werden alle drei Ausgänge zuerst aktiv auf 0 kW gesetzt;
- erst fünf gültige KSEM-Reads dürfen die Start-Sperre lösen;
- danach wird der inaktive DC-Satellit nur bei ausreichendem Headroom mit einem nicht autorisierenden 5-kW-Startwert vorgerüstet; globale Konfigurationswerte bleiben dabei unangetastet und es wird kein Startbefehl gesendet;
- beim ersten erkannten Vorgang wird der 5-kW-Wert über den vollständigen, autorisierten CCS-Pfad erneut übertragen und drei Sekunden gehalten;
- meldet EVCSD bei AC oder DC mehr als zentral freigegeben, stellt der 250-ms-Guard den niedrigeren Wert wieder her; eine anhaltende positive Sollwertverletzung beendet den Vorgang hart;
- ein neu hinzukommender AC- oder DC-Ladevorgang wird in die faire Gesamtverteilung aufgenommen, ohne eine unberechnete Vorbelegung.

## evcc-Wunschwerte

Register 110 und 111 sind dauerhafte Obergrenzen. Der LoadManager regelt daher
nicht mehr auf denselben veränderlichen EVCSD-Wert, sondern auf:

```text
P_freigegeben = min(P_evcc-Wunsch, P_sicheres Netzbudget)
```

`0 kW` bleibt 0 kW. Werte unter dem technischen Minimum werden ebenfalls als
Pause normalisiert. Nach einer Absenkung wird eine alte höhere Grid-Freigabe
verworfen; eine spätere evcc-Erhöhung muss erneut mit maximal 2 kW pro Zyklus
vom LoadManager freigegeben werden.

Nach einem JVM-/Webapp-Start arbeitet jeder Ausgang zunächst autonom mit seinem
konfigurierten Maximum als Wunschobergrenze. Die Start-, KSEM- und
Failback-Sperren halten die Hardware trotzdem so lange auf 0 kW, bis ein frisches
netzsicheres Ziel berechnet wurde. Der erste Modbus-Schreibzugriff übernimmt nur
den beschriebenen Ausgang für evcc; ein ausdrücklich geschriebenes `0 kW`
bleibt anschließend eine dauerhafte Pause.

## Zusammenspiel mit GridFailback

Der GridFailback startet vor dem LoadManager. Während Überstrom-, Trip- oder KSEM-Ausfallzuständen blockiert er jede Erhöhung. Der LoadManager setzt dann AC und DC ebenfalls wiederholt auf 0 kW. Erst nach der jeweiligen stabilen Freigabebedingung beginnt das gemeinsame Hochrampen erneut.

Quellcode: `https://github.com/BugUser0815/QC45/blob/native-integration/native-integration/src/main/java/de/rothner/qc45/LoadManager.java`

Siehe auch: [Grid-Failback](Grid-Failback), [KSEM-Anbindung](KSEM-Anbindung).
