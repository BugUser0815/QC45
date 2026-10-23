# LoadManager

Der native LoadManager regelt ausschließlich DC (CHAdeMO oder CCS) anhand der
höchsten vom KSEM gemessenen Netzphase. Gebäudelast, Type2-Laden und die
Unterstützung von PV/Batterie sind in diesem Messwert bereits enthalten.
Type2 wird durch den normalen LoadManager nicht dynamisch begrenzt.

## Stromsollwert aus der Konfiguration

Der aktive Sollwert ist das Minimum aus:

- dem Zeitprofil: `loadmanager.daytimeTargetA` (Standard 25 A) Mo–Sa
  06:30–18:00 Uhr Europe/Berlin, sonst die konfigurierte Freigabegrenze;
- `loadmanager.targetA`;
- `commandCeilingA`.

Die Freigabegrenze `commandCeilingA` ist das Minimum aus
`sls.nominalA`, `loadmanager.gridLimitA` und, bei aktiviertem Failback,
`failback.reduceA`. Die Hysterese ist ein Haltebereich und wird nicht zusätzlich
vom Ziel abgezogen. Beispiel mit der vorhandenen E35-Konfiguration:

| Größe | Wert |
|---|---:|
| Konfiguriertes Ziel | 34,0 A |
| Freigabegrenze | 35,0 A |
| Hysterese | 1,5 A |
| Wirksames Abendziel | **34,0 A** |
| Haltebereich einer erreichten Vorgabe | **32,5–35,5 A** |
| DC Minimum / hartes Maximum | **5 / 35 kW** |

Eine kurze Überschreitung der Freigabegrenze setzt DC nicht mehr sofort auf
5 kW zurück. Für eine spätere E50-Installation kann `sls.nominalA=50` zusammen
mit passenden Ziel-/Grenzwerten gesetzt werden; es gibt keine versteckte
35-A-Nachtgrenze mehr. Die separate DC-Leistungsgrenze bleibt bei 35 kW.
Bereits explizit gesetzte niedrigere Werte werden nicht automatisch erhöht.

## Regelung mit verzögerter Fahrzeugreaktion

Eine gültige evcc-Freigabe und fünf gültige KSEM-Abfragen sind Voraussetzung
für die reguläre DC-Regelung. Eine neue DC-Sitzung startet mit 5 kW.

Weitere Erhöhungen erfordern zwei aufeinanderfolgende DC-Leistungswerte,
deren niedrigerer Wert die aktuelle Vorgabe bis auf 1 kW erreicht hat. Auch
nach einer Absenkung muss die Station erst in diesen Bereich zurückkehren.
`loadmanager.rampUpKwPerLoop` begrenzt die Erhöhung zusätzlich. Nahe dem
Stromsollwert reduziert der verfügbare Headroom die Schritte auf 1 kW oder
hält die Vorgabe ganz fest.

Schon freigegebene, noch nicht abgerufene Leistung wird als mögliche
Zusatzlast berücksichtigt. Dafür gilt konservativ:

```text
Prognostizierter Phasenstrom = KSEM-Maximum
    + max(0, DC-Vorgabe - bestätigte DC-Leistung) / 0,60 kW/A
```

Die bestätigte DC-Leistung ist der niedrigere von zwei aufeinanderfolgenden
Messwerten. Ein einzelner hoher Wert kann daher keine weitere Erhöhung
freischalten. Eine noch nicht umgesetzte Absenkung erzeugt keinen zusätzlichen
Headroom. Nach Sitzungswechsel, Messfehler oder Schutzabsenkung wird diese
Bestätigung neu aufgebaut.

## Haltebereich und Reduktionen

Kleine Schwankungen innerhalb von ±`min(hysteresisA, 5 % des Sollwerts)` um das aktive
Stromziel ändern eine bereits erreichte Vorgabe nicht. Eine neue Erhöhung
muss mit ihrer gesamten ausstehenden Leistung unter dem Stromziel selbst
bleiben; sie darf den Haltebereich nicht als zusätzliche Reserve ausnutzen.

Oberhalb des Haltebereichs wird DC proportional abgesenkt. Die Berechnung
bezieht sich auf die bestätigte tatsächliche Leistung, damit ein unveränderter
hoher KSEM-Wert eine noch ausstehende Reduktion nicht mehrfach auslöst.
Reicht das Budget nicht für das technische Minimum, bleibt die Vorgabe logisch
pausiert. Ein nativer 0-kW-Wert wird wegen seiner mehrdeutigen Hardwarewirkung
weiterhin durch den physischen 5-kW-Notladeboden ersetzt.

## Schutz und evcc

KSEM-/Steuerungsfehler lösen Notladen mit erneuter Qualifikation aus.
Der unabhängige GridFailback reduziert größere Überschreitungen proportional;
anhaltende thermische Überlast und der konfigurierte Instant-Grenzwert lösen
einen Hard Trip aus. Sein Modell berücksichtigt Nennstrom, Belastungsdauer und
Abkühlung jeder Phase. Der normale Haltebereich setzt diese Schutzpfade nicht
außer Kraft.

DC wird höchstens bis zum aktuellen evcc-Budget freigegeben. Entzug oder
Ablauf dieser Freigabe pausiert die logische Regelung; der separate Wächter
beendet eine aktive DC-Sitzung ohne gültige Freigabe per RemoteStop.

## Diagnose und Prüfung

Die `LoadManager KSEM-direct`-Meldungen zeigen neben `phaseTarget`,
`releasedDC`, `targetDC` und `actualDC` jetzt auch `confirmedDC` und
`pendingDC`. Damit ist erkennbar, welcher Leistungsanteil schon bestätigt ist
und welcher noch als mögliche zusätzliche Netzlast reserviert wird.

Regressionstests decken die protokollierte Grenzsituation, Messrauschen,
verzögerte DC-Reaktionen und KSEM-Werte, beide Regler mit wiederholten
Lastspitzen, Messfehler, Freigabeentzug und Sitzungswechsel ab. E35 und E50
werden geprüft. Das Verhalten mit realem Fahrzeug wird nach Ausrollen
anhand derselben Werte überprüft.

Siehe auch: [Grid-Failback](Grid-Failback), [KSEM-Anbindung](KSEM-Anbindung).
