# Grid-Failback

`GridFailback` ist eine **unabhängige Schutzebene** neben dem normalen LoadManager. Er schützt den Netzanschlusspunkt jetzt vor der Summe aus DC und Type2/AC.

## Standard-Schutzstufen

| Stufe | Bedingung | Verzögerung | Aktion |
|---|---|---:|---|
| Reduce | Phase ≥ `34 A` | `500 ms` | DC und AC höchstens je `5 kW`; niemals hochsetzen |
| Over-limit pause | Phase ≥ `35 A` | sofort nach Messwert | DC `0 kW`, AC `0 kW`, Erhöhungen blockiert |
| SLS-E Hard Trip | zeit-/stromabhängig, siehe unten | Kennlinie | alle drei Connectoren stoppen und Latch setzen |
| Instant Trip | Phase ≥ `175 A` (`5 × In`) | sofort | alle drei Connectoren stoppen und Latch setzen |
| Meter Failure | KSEM ungültig | sofort | DC/AC `0 kW`, Sessions zunächst aktiv lassen |

Unabhängig vom KSEM überwacht der 250-ms-Limit-Guard die tatsächlich gemessene
Connectorleistung. Fließt nach 750 ms weiterhin Leistung, obwohl die wirksame
Freigabe 0 kW beträgt, wird die Transaktion abgebrochen und eine bis zum
Neustart verriegelte Leistungsfehler-Sperre gesetzt. Damit kann ein vom
CCS-Controller nicht umgesetztes 0-kW-Telegramm den Schutz nicht umgehen.

Abfrageintervall: **100 ms**.

Die Reduce-Stufe ist strikt reduktionsorientiert. Hat der LoadManager einen Ausgang bereits auf 0 kW gesetzt, darf der Failback ihn nicht wieder auf 5 kW anheben.

## Verhalten am Netzlimit

Schon der erste KSEM-Messwert ab `tripA` blockiert den LoadManager und setzt
beide Ladearten auf 0 kW. Der zusätzliche gelatchte Hard Trip folgt der
konservativen Software-Abbildung eines 35-A-SLS mit E-Charakteristik:

| Maximaler Phasenstrom | Dauer bis zum Hard Trip |
|---:|---:|
| `35 A` bis `< 36,75 A` (`< 1,05 × In`) | kein Latch-Timer; Pause bleibt aktiv |
| `36,75 A` bis `< 42 A` | `60 min` kontinuierlich |
| `42 A` bis `< 52,5 A` | `5 min` kontinuierlich |
| `52,5 A` bis `< 70 A` | `60 s` kontinuierlich |
| `70 A` bis `< 105 A` | `10 s` kontinuierlich |
| `105 A` bis `< 175 A` | `1 s` kontinuierlich |
| `≥ 175 A` | sofort |

Sinkt der Strom unter `1,05 × In`, wird die bis dahin gemessene Trip-Zeit
vollständig verworfen. Die Over-limit-Pause bleibt bestehen, bis fünf
aufeinanderfolgende Messungen unter `reduceA` liegen.

Damit führt eine kurze Überschreitung nicht sofort zum gelatchten Trip; es
bleibt aber während der gesamten Prüfung keine Ladeleistung freigegeben.
Für alle Kennlinien-Toleranzen gilt wegen der angenommenen thermischen
Vorbelastung des SLS ausdrücklich die jeweils kleinere Stromgrenze.

## Hard Trip

Beim Hard Trip:

1. wird der Latch gesetzt,
2. DC und AC werden auf 0 kW gesetzt,
3. CHAdeMO, CCS und Type2 erhalten `remoteStop()`, sofern aktiv,
4. fehlgeschlagene `remoteStop()`-Aufrufe werden alle zwei Sekunden erneut versucht,
5. die 0-kW-Limits werden bis zur Freigabe regelmäßig erneut durchgesetzt.

## Gelatchter Auto-Reset

Der Latch wird nach `failback.resetDelayMs` automatisch gelöscht, sobald alle
Phasen für die gesamte Wartezeit unter `reduceA` geblieben sind. Standard und
Minimum sind 60 Sekunden. Jeder KSEM-Lesefehler und jeder Messwert ab `reduceA`
setzt die Wartezeit wieder auf null. Solange noch eine Ladesitzung aktiv ist,
bleibt die Freigabe zusätzlich zurückgestellt; die wiederholten `remoteStop()`-
Versuche laufen in dieser Zeit weiter.

Der frühere Schalter `failback.autoResetHardTrip` ist obsolet und wird ignoriert.
Damit entsperrt auch eine vorhandene Konfiguration mit dem alten Wert `false`
nach der stabilen Wartezeit automatisch.

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
