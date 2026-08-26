# Grid-Failback

`GridFailback` ist eine **unabhängige Schutzebene** neben dem normalen LoadManager. Er schützt den Netzanschlusspunkt jetzt vor der Summe aus DC und Type2/AC.

## Standard-Schutzstufen

| Stufe | Bedingung | Verzögerung | Aktion |
|---|---|---:|---|
| Reduce | Phase ≥ `34 A` | `500 ms` | DC und AC höchstens je `5 kW`; niemals hochsetzen |
| Over-limit pause | Phase ≥ `35 A` | sofort nach Messwert | DC `0 kW`, AC `0 kW`, Erhöhungen blockiert |
| Persistenter Trip | Phase ≥ `35 A` | `250 ms` | alle drei Connectoren stoppen und Latch setzen |
| Instant Trip | Phase ≥ `38 A` | sofort | alle drei Connectoren stoppen und Latch setzen |
| Meter Failure | KSEM ungültig | `3000 ms` | DC/AC `0 kW`, Sessions zunächst aktiv lassen |

Abfrageintervall: **200 ms**.

Die Reduce-Stufe ist strikt reduktionsorientiert. Hat der LoadManager einen Ausgang bereits auf 0 kW gesetzt, darf der Failback ihn nicht wieder auf 5 kW anheben.

## Verhalten am Netzlimit

Schon der erste KSEM-Messwert ab `tripA` blockiert den LoadManager und setzt beide Ladearten auf 0 kW. Bleibt die Überschreitung für `tripDelayMs` bestehen, folgt der gelatchte Hard Trip. Fällt der Strom vorher wieder ab, bleibt die kurze Pause bestehen, bis fünf aufeinanderfolgende Messungen unter `reduceA` liegen.

Damit führt eine kurze Überschreitung nicht sofort zum Transaktionsabbruch; es bleibt aber während dieser Prüfung keine Ladeleistung freigegeben.

## Hard Trip

Beim Hard Trip:

1. wird der Latch gesetzt,
2. DC und AC werden auf 0 kW gesetzt,
3. CHAdeMO, CCS und Type2 erhalten `remoteStop()`, sofern aktiv,
4. die 0-kW-Limits werden bis zur Freigabe regelmäßig erneut durchgesetzt.

## Auto-Reset

Der Hard-Trip-Latch wird automatisch gelöscht, wenn alle KSEM-Lesungen gültig sind und jede Phase standardmäßig **60 s** ununterbrochen unter `reduceA` bleibt. Jeder erneute Überstrom oder KSEM-Fehler startet den Timer neu.

## KSEM-Ausfall

Nach drei Sekunden ohne gültigen KSEM-Wert werden AC und DC auf 0 kW pausiert, ohne die Backend-Transaktionen sofort abzubrechen. Nach fünf stabilen gültigen Reads unter 34 A darf der LoadManager beide Ausgänge wieder gemeinsam hochfahren.

Quellcode: `https://github.com/BugUser0815/QC45/blob/native-integration/native-integration/src/main/java/de/rothner/qc45/GridFailback.java`

Siehe auch: [LoadManager](LoadManager).
