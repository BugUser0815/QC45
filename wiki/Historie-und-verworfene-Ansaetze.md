# Historie & verworfene Ansätze

Diese Seite ist absichtlich deutlich: Sie soll verhindern, dass bereits widerlegte oder ersetzte Ansätze später versehentlich erneut eingebaut werden.

## QC45 / CCS

### ⛔ Ampere in CCS-V3-Byte 2

Verworfen. Die Dekompilierung des Original-`QuickChargeSerializer` belegt, dass Byte 2 des CCS-V3-START-Pakets `maxPower` in **kW** enthält. Ein aus `P/U` berechneter Stromwert dort ist falsch.

### ⛔ `quickChargeMaxCurrent` als alleinige CCS-Regelung

Verworfen. `sendCcsStart()` setzt dieses Feld zwar, der CCS-V3-Serializer verwendet für den START-Befehl aber `maxPower`.

### ⛔ Rückfall auf internes EFACEC CCS V2

Keine Lösung. Der analysierte V2-START transportiert keinen vergleichbaren dynamischen Leistungswert. Der produktive Stand erzwingt V3.

### ⚠️ Alte Kona-Logs

Logs aus der Zwischenversion mit `ccsV3Byte2=<Ampere>` zeigen, dass der Kona hochlief, beweisen aber **nicht**, dass der heutige korrigierte kW-Frame physisch gesendet wurde. Dafür ist ein frischer Raw-Trace nötig.

## Load Balancing

### ⛔ EVCSD darf beim Start selbständig auf 50 kW springen

Ein 5-kW-Pre-Arm war ebenfalls nicht ausreichend sicher, weil er vor der
eigentlichen Headroom-Berechnung Leistung freigab. Der aktuelle Stand startet
fail-closed bei 0 kW. Erst nach fünf gültigen KSEM-Messungen wird ein frisch
berechnetes Ziel unter aktiver Sperre vorbereitet und anschließend freigegeben.
Der `ChargingLimitGuard` setzt fremde EVCSD-Änderungen zurück.

### ⛔ Alte Type2-Mitregelung ohne heutige Schutzmechanismen

Die frühe Hybridfassung wurde zu Recht verworfen: Sie verwendete gemeldete statt autoritative Limits und enthielt die spätere Start-, Freigabe- und GridFailback-Absicherung nicht. Seit der AC/DC-Neufassung wird Type2 wieder aktiv geregelt, aber über ein gemeinsames 50/50-Budget, autoritative AC/DC-Sollwerte, Reduktion-vor-Erhöhung und einen gemeinsamen Schutzpfad. Die alte Implementierung darf nicht wiederhergestellt werden.

## KSEM

### ⛔ Eine einzige dauerhafte KSEM-Verbindung als Zwang

Nicht übernommen. Wegen paralleler Zugriffe durch QC45, PeakShaving, evcc/Monitoring sind kurze Verbindungen robust und ausreichend.

### ⛔ Nur das untere 16-Bit-Wort des Phasenstroms lesen

Verworfen. Oberhalb 65,535 A läuft das Low-Word über und kann einen gefährlich
hohen Strom als kleinen Wert erscheinen lassen. Es wird immer der vollständige
unsigned 32-Bit-Wert in der konfigurierten Wortreihenfolge ausgewertet.

## Peak Shaving

### ⛔ Python + RAM-Disk + C++-Parser

Ersetzt durch einen einzigen C++-Prozess, der KSEM direkt per Modbus liest und Speedwire direkt sendet.

### ⛔ Integrator `fakeW += errorW`

Zwischenzeitlich getestet, anschließend zurückgenommen. In Sonderfällen führte die akkumulierte Regelung zu unerwünschtem Speicherverhalten. Der aktuelle `lean`-Stand verwendet wieder die statische Beziehung aus realem Nettofluss und Peak-Ziel.

### ⛔ Fake Export bei fehlender Last auf 0 setzen

Falsch für das gewünschte Systemverhalten. Fake Export wird benötigt, um die Pufferbatterie zu beladen. Der richtige Sonderfall ist, **reale Einspeisung/keine Last nicht als Entladebedarf zu interpretieren**, während Fake Export bestehen bleibt.

## OCPP

### Historischer direkter OCPP-Client

`OcppClient.java` und die nur dafür benötigte `TlsSupport.java` wurden entfernt.
Der produktive Stand verwendet ausschließlich:

- `OcppBridgeClient`
- `Ocpp15BridgeServer`

also die Legacy-EVCSD-1.5-SOAP-Bridge auf OCPP 1.6 JSON/WSS.

## UI

Die Ladeanzeige sollte **nicht** neu designt werden. Finaler Wunsch war: Original-Layout beibehalten, nur technisch korrekte kW-Anzeige und sinnvolle Zusatzinformationen. Frühere Layoutabweichungen sind daher kein Sollstand.

---

Bei Unsicherheit gilt: zuerst den aktuellen Branch und dieses Wiki prüfen, bevor alte Logs oder Zwischen-JARs als Referenz verwendet werden.
