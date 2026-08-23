# Grid-Failback

`GridFailback` ist eine **unabhängige Schutzebene** neben dem normalen LoadManager. Sein Ziel ist nicht Komfort oder optimale Auslastung, sondern das schnelle Begrenzen bzw. Stoppen der DC-Ladung bei kritischem Netzstrom.

## Standard-Schutzstufen

| Stufe | Bedingung | Verzögerung | Aktion |
|---|---|---:|---|
| Reduce | Phase ≥ `34 A` | `500 ms` | DC-Budget auf `5 kW` |
| Trip | Phase ≥ `35 A` | `250 ms` | Hard Trip |
| Instant Trip | Phase ≥ `38 A` | sofort | Hard Trip |
| Meter Failure | KSEM ungültig | `3000 ms` | DC `0 kW`, Session bleibt aktiv |

Abfrageintervall: **200 ms**.

## Hard Trip

Beim Hard Trip:

1. wird der Latch gesetzt,
2. DC wird auf das Reduktionsbudget gesetzt,
3. Connector 1 und 2 erhalten `remoteStop()`,
4. Type2 / Connector 3 bleibt unangetastet.

Der Latch verhindert, dass unmittelbar wieder hochgeregelt wird.

## Auto-Reset

Der heutige Stand benötigt **keinen Not-Aus-Taster mehr zum Rücksetzen**. Der Hard-Trip-Latch wird automatisch gelöscht, wenn:

- alle KSEM-Lesungen gültig sind,
- jede Phase unter `reduceA` bleibt,
- dieser Zustand standardmäßig **60 s** ununterbrochen anhält.

Jeder erneute Überstrom oder KSEM-Fehler setzt den Reset-Timer zurück.

## KSEM-Ausfall ≠ Hard Trip

Ein Kommunikationsausfall wird separat als `meterPaused` behandelt:

```text
KSEM >3 s nicht lesbar
        ↓
DC Budget = 0 kW
Transaktion bleibt bestehen
        ↓
5 stabile gültige Reads unter 34 A
        ↓
Pause frei; LoadManager darf wieder hochregeln
```

Damit wird bei einem kurzzeitigen Messproblem nicht unnötig eine Backend-Transaktion beendet.

## Warum Type2 unberührt bleibt

Der aktuelle Schutzpfad wurde bewusst auf DC fokussiert. Type2-Verbrauch ist trotzdem im KSEM enthalten und beeinflusst somit den DC-Headroom. Eine aktive Manipulation oder ein Stop von Connector 3 findet im aktuellen Code nicht statt.

Quellcode: `https://github.com/BugUser0815/QC45/blob/native-integration/native-integration/src/main/java/de/rothner/qc45/GridFailback.java`

Siehe auch: [LoadManager](LoadManager).
