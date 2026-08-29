# LoadManager

Der native LoadManager arbeitet seit dem AC-Prioritäts-Umbau nach einem einfachen Prinzip:

> **AC wird im normalen LoadManager nicht nach Netzleistung gedrosselt. DC erhält nur die Leistung, die am Netzanschlusspunkt noch übrig ist.**

CHAdeMO und CCS bleiben untereinander alternativ; parallel vorgesehen ist ein DC-Fahrzeug plus ein Type2/AC-Fahrzeug.

## Standardwerte

| Parameter | Wert |
|---|---:|
| Zielstrom | `32.0 A` |
| hartes konfiguriertes Netzlimit | `35.0 A` |
| wirksame Freigabegrenze mit Failback | `34.0 A` |
| Hysterese | `0.8 A` |
| DC Minimum / Maximum | `5 / 50 kW` |
| AC konfiguriertes Maximum | `43 kW` |
| DC Ramp-up | `2 kW / Regelzyklus` |
| Regelintervall | `1000 ms` |
| KSEM-Reads bis Freigabe | `5` |

Die wirksame Freigabegrenze ist das Minimum aus `loadmanager.gridLimitA` und `failback.reduceA`. Dadurch bleibt vor dem 35-A-Netzlimit eine zusätzliche Reserve für den geregelten DC-Pfad.

## AC hat Vorrang

Der KSEM misst am Netzanschlusspunkt bereits die Summe aus:

- Gebäudelast,
- AC-Ladevorgang,
- DC-Ladevorgang,
- sonstigen Verbrauchern.

Deshalb muss der LoadManager AC nicht noch einmal als eigenes Teilbudget behandeln. Die höchste der drei gemessenen Phasen ist direkt die Grundlage für die DC-Freigabe.

AC erhält im normalen Betrieb seine konfigurierte Obergrenze beziehungsweise eine ausdrücklich von evcc gesetzte AC-Obergrenze. Der LoadManager teilt kein gemeinsames AC/DC-Budget mehr auf und führt keine 50/50-Verteilung oder bedarfsgerechte Umverteilung mehr durch.

Beispiel:

```text
Netzziel:           32 A
KSEM höchste Phase: 20 A   <- enthält AC + Gebäude + aktuellen DC-Anteil
Rest:               12 A
```

Aus diesem Rest wird ausschließlich der nächste DC-Sollwert berechnet. Steigt die AC-Leistung, steigt der vom KSEM gemessene Phasenstrom und der DC-Sollwert sinkt im nächsten Regelzyklus automatisch. Fällt AC-Leistung weg, kann DC wieder hochrampen.

## DC-Residualregelung

Für DC gilt weiterhin die dreiphasige Näherung zur Bestimmung des grundsätzlich verfügbaren Headrooms:

```text
1 A ≈ √3 × 400 V / 1000 = 0,69282 kW
```

Die abschließende Sicherheitsprojektion rechnet DC konservativer mit:

```text
0,60 kW/A
```

Damit werden Verluste, Spannungsabweichungen und verzögerte Fahrzeugreaktionen berücksichtigt.

Die DC-Regelung arbeitet dabei so:

1. KSEM liefert L1/L2/L3; maßgeblich ist die höchste Phase.
2. `targetA - maxPhaseA` bestimmt den noch verfügbaren Headroom.
3. Nur DC wird aus diesem Headroom neu berechnet.
4. DC-Erhöhungen sind auf `rampUpKwPerLoop` begrenzt.
5. Die nächste Erhöhung wird erst freigegeben, wenn das Fahrzeug die vorherige Freigabe nahezu erreicht hat.
6. Reduktionen erfolgen sofort.
7. Bereits freigegebene, aber vom Fahrzeug noch nicht abgerufene DC-Leistung wird konservativ als mögliche Zusatzlast berücksichtigt.
8. Neu gemessene DC-Leistung wird erst nach einem weiteren Zyklus vollständig als im KSEM-Messwert enthalten gutgeschrieben.

Dadurch kann ein träge reagierendes CCS-Fahrzeug nicht mehrere Erhöhungen ansammeln und später schlagartig oberhalb des sicheren Netzbudgets landen.

## Verhalten von AC

Der normale LoadManager schreibt für AC keine aus dem Netz-Headroom abgeleitete Leistungsgrenze mehr.

Es gelten weiterhin zwei andere Obergrenzen:

- das konfigurierte AC-Maximum der Station, standardmäßig `43 kW`;
- eine ausdrücklich von evcc gesetzte AC-Obergrenze.

Ein evcc-Wert von `0 kW` bleibt damit weiterhin eine bewusste Pause. Ohne einen solchen expliziten Eingriff arbeitet AC mit seinem konfigurierten Maximum.

Auch ein inaktiver AC-Ausgang wird nicht mehr mit einem kleinen, vom Netzbudget berechneten Startwert vorgerüstet. Der nicht autorisierende Pre-Arm verwendet die normale AC-Obergrenze, damit ein neu gestarteter AC-Vorgang nicht zunächst künstlich gedrosselt wird.

## Startabsicherung für DC

Die Startabsicherung bleibt für den geregelten DC-Pfad bestehen:

- beim JVM-/Webapp-Start blockiert die Sicherheitslogik zunächst die Ladefreigabe;
- fünf gültige KSEM-Reads sind erforderlich, bevor die Start-Sperre gelöst wird;
- ein inaktiver DC-Satellit wird nur bei ausreichendem Headroom nicht autorisierend mit dem technischen Mindestwert vorgerüstet;
- beim ersten erkannten DC-Vorgang wird dieser Mindestwert über den vollständigen autorisierten Pfad erneut übertragen und drei Sekunden gehalten;
- danach beginnt das kontrollierte DC-Ramp-up.

Die globale Start- und Fehlerabsicherung bleibt bewusst erhalten. „AC unbeschränkt“ bedeutet ausschließlich, dass der **normale Netz-Allocator** AC nicht dynamisch herunterregelt.

## Oberhalb der normalen DC-Freigabegrenze

Erreicht die höchste KSEM-Phase die `commandCeilingA`, setzt der normale LoadManager DC sofort auf `0 kW`. AC bleibt auf seiner normalen Obergrenze.

Das ist absichtlich so: AC besitzt Priorität, DC ist die nachgeordnete variable Last.

Eine echte Überlast des Netzanschlusspunktes wird weiterhin vom separaten **GridFailback** behandelt. Dadurch bleiben zwei Aufgaben sauber getrennt:

```text
LoadManager:  AC frei, DC = Restleistung
GridFailback: unabhängiger Schutz des SLS bei Überlast/Fehler
```

## Zusammenspiel mit GridFailback

Der GridFailback bleibt unverändert die letzte Schutzinstanz und darf AC und DC gemeinsam begrenzen oder stoppen. Das betrifft insbesondere:

- KSEM-Ausfall,
- länger anstehende Überlast,
- die SLS-E-Zeit/Strom-Kennlinie,
- Hard-Trip-Zustände,
- sicherheitskritische Kontrollfehler.

Damit kann AC im normalen Betrieb Vorrang haben, ohne die unabhängige Schutzfunktion für den 35-A-SLS zu verlieren.

## evcc-Wunschwerte

DC:

```text
P_DC = min(P_evcc-DC, P_DC-aus-Netzrest, P_Failback)
```

AC:

```text
P_AC = min(P_evcc-AC, P_AC-Konfiguration, P_Failback)
```

Wenn evcc für einen Ausgang noch nie einen Wert geschrieben hat, verwendet die native Integration dessen konfiguriertes Maximum. Ein ausdrücklich geschriebenes `0 kW` bleibt anschließend eine dauerhafte Pause, bis evcc wieder einen anderen Wert setzt.

## Erwartete Logs

Im neuen Betriebsschema erscheinen LoadManager-Meldungen mit:

```text
priority=AC-first/DC-residual
```

Bei Erreichen der normalen Freigabegrenze:

```text
LoadManager GUARD ... -> DC=0kW AC=unrestricted; GridFailback remains authoritative
```

Damit ist im Log eindeutig erkennbar, dass eine DC-Abregelung nicht mehr gleichzeitig eine normale AC-Abregelung auslöst.

Quellcode: `native-integration/src/main/java/de/rothner/qc45/LoadManager.java`

Siehe auch: [Grid-Failback](Grid-Failback), [KSEM-Anbindung](KSEM-Anbindung).
