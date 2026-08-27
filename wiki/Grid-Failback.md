# Grid-Failback

`GridFailback` ist eine **unabhängige Schutzebene** neben dem normalen LoadManager. Er schützt den Netzanschlusspunkt jetzt vor der Summe aus DC und Type2/AC.

## Standard-Schutzstufen

| Stufe | Bedingung | Verzögerung | Aktion |
|---|---|---:|---|
| Reduce | Phase ≥ `34 A` | `500 ms` | DC und AC höchstens je `5 kW`; niemals hochsetzen |
| Over-limit pause | Phase ≥ `35 A` | sofort nach Messwert | DC `0 kW`, AC `0 kW`, Erhöhungen blockiert |
| Persistenter Trip | Phase ≥ `35 A` | `250 ms` | alle drei Connectoren stoppen und Latch setzen |
| Instant Trip | Phase ≥ `38 A` | sofort | alle drei Connectoren stoppen und Latch setzen |

Unabhängig vom KSEM überwacht der 250-ms-Limit-Guard die tatsächlich gemessene
Connectorleistung. Fließt nach 750 ms weiterhin Leistung, obwohl die wirksame
Freigabe 0 kW beträgt, wird die Transaktion abgebrochen und eine bis zum
Neustart verriegelte Leistungsfehler-Sperre gesetzt. Damit kann ein vom
CCS-Controller nicht umgesetztes 0-kW-Telegramm den Schutz nicht umgehen.
| Meter Failure | KSEM ungültig | sofort | DC/AC `0 kW`, Sessions zunächst aktiv lassen |

Abfrageintervall: **100 ms**.

Die Reduce-Stufe ist strikt reduktionsorientiert. Hat der LoadManager einen Ausgang bereits auf 0 kW gesetzt, darf der Failback ihn nicht wieder auf 5 kW anheben.

## Verhalten am Netzlimit

Schon der erste KSEM-Messwert ab `tripA` blockiert den LoadManager und setzt beide Ladearten auf 0 kW. Bleibt die Überschreitung für `tripDelayMs` bestehen, folgt der gelatchte Hard Trip. Fällt der Strom vorher wieder ab, bleibt die kurze Pause bestehen, bis fünf aufeinanderfolgende Messungen unter `reduceA` liegen.

Damit führt eine kurze Überschreitung nicht sofort zum Transaktionsabbruch; es bleibt aber während dieser Prüfung keine Ladeleistung freigegeben.

## Hard Trip

Beim Hard Trip:

1. wird der Latch gesetzt,
2. DC und AC werden auf 0 kW gesetzt,
3. CHAdeMO, CCS und Type2 erhalten `remoteStop()`, sofern aktiv,
4. fehlgeschlagene `remoteStop()`-Aufrufe werden alle zwei Sekunden erneut versucht,
5. die 0-kW-Limits werden bis zur Freigabe regelmäßig erneut durchgesetzt.

## Gelatchter Reset

Standardmäßig gibt es **keinen automatischen Reset nach Zeit**. Der Latch wird
erst nach einer erkannten E-STOP-Betätigung, anschließendem Loslassen und fünf
gültigen KSEM-Reads unter `reduceA` gelöscht. So kann eine fortbestehende
Fehlerursache nicht nach 60 Sekunden selbsttätig wieder freigeben.

Ein zeitgesteuerter Reset ist nur als ausdrückliches Opt-in mit
`failback.autoResetHardTrip=true` und `failback.resetDelayMs` verfügbar.

## KSEM-Ausfall

Schon der erste fehlgeschlagene oder unplausible KSEM-Read pausiert AC und DC
auf 0 kW, ohne die Backend-Transaktionen sofort abzubrechen. Nach fünf stabilen
gültigen Reads unter 34 A wird zunächst jede alte Grid-Freigabe verworfen; erst
danach darf der LoadManager beide Ausgänge neu hochfahren.

Dasselbe Nullsetzen vor Freigabe gilt beim Ende der Reduce-Stufe. Eine alte
50-kW-Freigabe kann dadurch beim Entfernen einer 5-kW-Schutzkappe nicht
sprunghaft wieder wirksam werden.

Quellcode: `https://github.com/BugUser0815/QC45/blob/native-integration/native-integration/src/main/java/de/rothner/qc45/GridFailback.java`

Siehe auch: [LoadManager](LoadManager).
