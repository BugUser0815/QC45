# Grid-Failback

`GridFailback` überwacht unabhängig vom DC-LoadManager alle drei KSEM-Phasen.
Die Stromgrenzen beziehen sich auf `sls.nominalA` (Standard 35 A) und die
E-Charakteristik. Type2 bleibt im aktuellen Betrieb ungeregelt; seine Last ist
im KSEM-Messwert enthalten. Nur vom Coordinator verwaltete Ausgänge werden
begrenzt oder gestoppt.

## Proportionale Reduktion

| Stufe | Auslöser bei E35 mit Standardwerten | Reaktion |
|---|---|---|
| Reduce | mindestens 36 A für 500 ms | Leistung um den gemessenen Stromüberschuss absenken |
| Fast Reduce (`tripA`) | mindestens 38 A für 250 ms | dieselbe proportionale Reduktion früher auslösen |
| Thermischer Hard Trip | akkumulierte Überlast einer Phase | Ladefreigabe sperren, aktive verwaltete Sitzungen stoppen |
| Instant Trip | mindestens 175 A (`5 × In`) oder niedrigerer konfigurierter Wert | sofortiger Hard Trip |
| Mess-/Steuerungsfehler | ungültige KSEM-Daten oder fehlgeschlagene Durchsetzung | sofortige Sperre mit erneuter Messwertqualifikation |

Abfrageintervall: höchstens 100 ms. Normale Reduktionen erfolgen höchstens
einmal je Sekunde, damit die Station reagieren kann. `reduceDelayMs` und
`tripDelayMs` bleiben wirksam, begrenzt auf höchstens 500 beziehungsweise 250 ms.
`tripA` ist jetzt eine schnellere Reduktionsschwelle, keine sofortige Pause.
Eine leichte Überschreitung des Nennstroms setzt die Sitzung nicht auf 5 kW
zurück.

Der Stromüberschuss wird gegenüber `min(In, reduceA)` berechnet. Bei DC wird
je überschüssigem Ampere um rund 0,693 kW reduziert, auf ganze kW aufgerundet.
Ausgangspunkt ist das Maximum aus wirksamer Vorgabe und beobachteter Leistung;
eine noch nicht umgesetzte Absenkung wird dadurch nicht erneut abgezogen.
Der Failback darf eine Vorgabe niemals erhöhen. `reduceDcKw`/`reduceAcKw`
bezeichnen den unteren Endpunkt der schrittweisen Absenkung (0 oder das
technische Minimum), nicht mehr eine sofort aufzuerlegende 5-kW-Kappe.

Nach fünf aufeinanderfolgenden Messungen unter `min(In, reduceA)` entfällt
die Schutzkappe **bei der aktuellen Zuteilung**. Der LoadManager darf danach
normal weiterregeln. Weder eine alte hohe Vorgabe noch ein Neustart bei 5 kW
wird dabei erzwungen. evcc-Freigabe, ausstehende DC-Leistung und Fahrzeugreaktion
bleiben berücksichtigt.

## Thermische Vorgeschichte

Hager beschreibt die E-Charakteristik als Auslöseband: thermisch
`1,05–1,2 × In`, magnetisch `5–6,25 × In`. Bei `1,05 × In` muss der Schalter
unter den Prüfbedingungen länger als eine Stunde halten; bei `1,2 × In`
ist die Stunde eine obere Auslösezeit, keine garantierte Mindesthaltezeit.
Die frühere Tabelle mit bis zu einer Stunde Wartezeit und vollständigem
Timer-Reset bei jedem Stromrückgang wurde daher entfernt.

Die zusätzliche Softwareüberwachung verwendet für jede Phase einen eigenen
normierten I²-Zustand mit exponentieller Abkühlung:

```text
h_neu = h_alt * exp(-dt / tau) + (I / In)^2 * (1 - exp(-dt / tau))
```

Für das vergangene Messintervall wird vorsichtig der größere der beiden
angrenzenden Stromwerte verwendet. Bei Start gilt `h=1` (bereits mit Nennstrom
belastet), die Eingriffsschwelle ist `h > 1,05²`. `tau` wird über
`sls.thermalTimeConstantMs` vorgegeben (Standard/Maximum 30000 ms, Minimum
1000 ms). Kleinere Werte beschleunigen die Reaktion auf anhaltende Überlast.
Dies ist eine bewusst schnelle **Software-Schutzstrategie**, keine kalibrierte
Hager-Kennlinie und keine zugesicherte physikalische Auslösezeit. Die originale
SLS-Schutzeinrichtung bleibt maßgeblich.

Leichtes Pendeln um den Nennstrom wird zugelassen. Wiederholte Belastungen
akkumulieren sich; ein einzelner niedriger Messwert setzt den Zustand nicht
zurück. Bei Messausfall wird keine Abkühlung angenommen. Nach einem JVM-Neustart
beginnt das Modell wieder mit der genannten Vorbelastung; der Zustand wird
nicht über Neustarts gespeichert.

## Hard Trip und Wiederfreigabe

Ein Hard Trip sperrt die Ladefreigabe logisch auf 0 kW und sendet RemoteStop
an aktive verwaltete Connectoren. Fehlgeschlagene Stopps werden alle zwei
Sekunden erneut versucht. Die nativen Limits werden weiter durchgesetzt.
Der physische 5-kW-Notladeboden ist kein sicheres Aus; der unabhängige
Leistungswächter kontrolliert deshalb weiterhin, ob ein gesperrter Ausgang
Leistung zieht.

Der automatische Reset erfordert gleichzeitig:

- alle Phasen durchgehend unter `min(In, reduceA)`;
- thermische Zusatzbelastung auf höchstens 25 % der Eingriffsschwelle abgebaut;
- anschließend mindestens `failback.resetDelayMs` stabile Messwerte
  (Standard/Minimum 60000 ms);
- keine aktive Ladesitzung mehr an einem der drei Connectoren.

Jeder Messfehler oder erneute Grenzwertverstoß startet die Wartezeit neu.
Der thermische Zustand bleibt auch beim Reset erhalten. Der frühere Schalter
`failback.autoResetHardTrip` ist obsolet und wird ignoriert.

Bei einem KSEM-/Steuerungsfehler sperrt der Failback sofort. Nach fünf gültigen
Messungen unter `min(In, reduceA)` wird zunächst die alte Grid-Freigabe verworfen;
der LoadManager bereitet eine frische Freigabe ab dem technischen Minimum vor.
Diese Fehlererholung unterscheidet sich von der normalen proportionalen Reduktion.

## Konfiguration und Quellen

Alle explizit gesetzten niedrigeren Stromschwellen bleiben wirksam. Der Code
hebt insbesondere einen kleineren `instantTripA`-Wert nicht mehr automatisch
auf 175 A an. Überhöhte Werte werden abhängig vom eingestellten SLS begrenzt
und im Startlog ausgewiesen. Für E50 skalieren Modell und magnetische Grenze
auf 50 A beziehungsweise 250 A; vorhandene explizite E35-Grenzen müssen für
die neue Installation bewusst angepasst werden.

- [Hager: E-Auslöseband und technische Daten, Seiten 1 und 3](https://assets.hager.com/step-content/P/HA_22838894/Document/std.lang.all/22DE0001-TECHNIK-SLS-SCHALTER.pdf)
- [Hager: Erklärung der Prüfströme und Auslösezeiten](https://hager.com/de/wissen/e-volution/produktwissen/selektive-hauptleitungsschutzschalter)
- [Konfiguration und Betrieb](Konfiguration-und-Betrieb)
- [LoadManager](LoadManager)
