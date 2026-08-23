# Hyundai Kona: CCS-Leistungsbegrenzung wird ignoriert

**Stand:** 23.08.2026  
**Betroffener Pfad:** EFACEC QC45 / EVCSD / CCS / DIN SPEC 70121  
**Status:** Ursache stark eingegrenzt, finale Bestätigung am CCS-Controller steht noch aus.

## Kurzfassung

Beim Hyundai Kona wird die von der QC45 vorgegebene DC-Ladeleistung nicht zuverlässig eingehalten. Die Java-/EVCSD-Seite setzt den gewünschten Leistungswert korrekt bis zur internen EFACEC-QuickCharge-Schnittstelle. Der entscheidende Verdacht liegt inzwischen **hinter** EVCSD: Der nachgeschaltete CCS-Kommunikationscontroller dürfte den EFACEC-Leistungswert als `EVSEMaximumPowerLimit` an das Fahrzeug weitergeben, ohne gleichzeitig `EVSEMaximumCurrentLimit` dynamisch zu reduzieren.

Das passt exakt zu dokumentiertem Verhalten des Hyundai Kona: Eine wissenschaftliche Untersuchung mit kommerziellen Fahrzeugen beschreibt, dass ein Hyundai Kona (2019) den von der EVSE gesetzten `EVSEMaximumPowerLimit` ignorierte, während getestete Fahrzeuge dynamische `EVSEMaximumCurrentLimit`-Vorgaben befolgten.

Für die Lösung muss daher wahrscheinlich **nicht der LoadManager**, sondern die Umsetzung auf dem CCS-Controller angepasst werden: aus der gewünschten Leistung muss bei laufender Ladung eine passende Stromgrenze erzeugt und als `EVSEMaximumCurrentLimit` in der `CurrentDemandRes`-Schleife an das Fahrzeug gegeben werden.

---

## 1. Problembeobachtung

Die native QC45-Integration kann beispielsweise 5 kW oder 6 kW als DC-Sollwert setzen. Beim Kona steigt die reale Ladeleistung trotzdem weiter an.

In den bisherigen Versuchen wurden unter anderem Größenordnungen wie folgende beobachtet:

- Sollwert 5–6 kW
- DC-Spannung etwa 360–365 V
- tatsächlicher Strom steigt über 20 A, 40 A, 50 A bis über 70 A
- reale Ladeleistung steigt entsprechend deutlich über den Sollwert

Der Fehler ist damit nicht einfach ein Anzeigeproblem: die QC45 liefert real mehr Leistung als vorgegeben und kann dadurch den Netz-Failback auslösen.

Wichtig: Ein Teil der vorhandenen Logs stammt aus einer **verworfenenen Zwischenversion**, in der fälschlich ein aus Leistung/Spannung berechneter Amperewert in Byte 2 des EFACEC-V3-Pakets geschrieben wurde. Diese Logs beweisen das Fahrzeugverhalten, **beweisen aber nicht**, dass im aktuellen korrigierten Stand physisch `05` für 5 kW auf der Leitung gesendet wurde. Dieser Nachweis muss mit dem aktuellen Branch noch einmal sauber erfolgen.

---

## 2. Zwei getrennte Protokollebenen

Für die Fehlersuche ist die Trennung der beiden Kommunikationsstrecken entscheidend.

### Ebene A: EVCSD / Linux -> EFACEC-QuickCharge-System

Die Java-Anwendung EVCSD kommuniziert über ein proprietäres EFACEC-Protokoll mit dem nachgeschalteten QuickCharge-System.

Im Repo relevant:

- [`CcsProtocolV3Enforcer.java`](../native-integration/src/main/java/de/rothner/qc45/CcsProtocolV3Enforcer.java)
- [`ReflectionQC45.java`](../native-integration/src/main/java/de/rothner/qc45/ReflectionQC45.java)
- [`CcsRawTracerV2.java`](../native-integration/src/main/java/de/rothner/qc45/CcsRawTracerV2.java)
- [`LoadManager.java`](../native-integration/src/main/java/de/rothner/qc45/LoadManager.java)

### Ebene B: CCS-Controller -> Fahrzeug

Der eigentliche CCS-Controller kommuniziert über PLC/SLAC und DIN SPEC 70121 bzw. ISO 15118 mit dem Fahrzeug.

Während des aktiven DC-Ladens werden unter anderem `CurrentDemandReq` und `CurrentDemandRes` regelmäßig ausgetauscht. Auf dieser Ebene existieren getrennte Grenzwerte für Leistung und Strom, unter anderem:

- `EVSEMaximumPowerLimit`
- `EVSEMaximumCurrentLimit`
- `EVSEMaximumVoltageLimit`

**Das interne EFACEC „CCS V2/V3“ ist nicht die vom Fahrzeug ausgehandelte DIN-/ISO-Protokollversion.** Es ist eine interne EFACEC-Protokollgeneration zwischen EVCSD und QuickCharge-Hardware.

---

## 3. Bestätigt: Verhalten des originalen EVCSD

Das originale `evcsd.jar` wurde dekompiliert und insbesondere folgende Klassen untersucht:

- `pt.efacec.es.mobie.agent.stationcomponents.comms.quickcharge.QuickChargeSerializer`
- `pt.efacec.es.mobie.agent.stationcomponents.SatelliteModule`
- `MessageStateMachines`

### 3.1 `SatelliteModule.sendCcsStart()`

Beim Senden eines CCS-Startkommandos baut EVCSD ein `MessageStateMachines`-Objekt auf und setzt unter anderem:

- `satelliteAction = START_CHARGE`
- `device = SATELLITE_CCS_CHARGE`
- `current = quickChargeMaxCurrent`
- `maxPower = satelliteMaxPower`
- Login-/Autorisierungsstatus

Danach wird die Nachricht über `CentralStationComms.send()` verschickt.

### 3.2 CCS V3 verwendet `maxPower`, nicht `current`

Der entscheidende Befund aus `QuickChargeSerializer`:

Für **CCS V3** wird beim START-Paket der Wert aus `MessageStateMachines.maxPower` verwendet.

Das resultierende QuickCharge-Paket besteht aus fünf Bytes:

```text
0x63  <flags>  <maxPower_kW>  <CRC16 low/high>
```

Beispiel für 5 kW, abhängig vom Flag-/Loginstatus sinngemäß:

```text
63 02 05 xx xx
```

oder

```text
63 82 05 xx xx
```

Byte 2 ist damit **Leistung in kW**.

### 3.3 `quickChargeMaxCurrent` ist im V3-CCS-START wirkungslos

Obwohl `sendCcsStart()` auch das Feld `current` setzt, verwendet der CCS-V3-Serializer dieses Feld nicht für den START-Befehl.

Daraus folgt:

> Ein aus `P/U` berechneter Amperewert darf **nicht** in Byte 2 des V3-Pakets geschrieben werden.

Der frühere Versuch, dort z. B. 14 für 14 A einzutragen, war protokollseitig falsch. Für 5 kW muss dort `05` stehen.

### 3.4 CCS V2 ist ebenfalls keine Lösung

Der untersuchte CCS-V2-START-Pfad enthält keinen vergleichbaren dynamischen Leistungswert. Ein Zurückfallen auf die interne EFACEC-V2-Variante würde die Regelung daher nicht verbessern.

Der aktuelle Branch erzwingt deshalb mit `CcsProtocolV3Enforcer` gezielt die V3-Verwendung.

---

## 4. Aktueller Stand der nativen Integration

### 4.1 `CcsProtocolV3Enforcer`

Die native Integration erzwingt `QCProtocolVersion = 3` an den relevanten EVCSD-Objekten bzw. Serializern. Die Anwendung soll fehlschlagen, wenn die V3-Erzwingung nicht gelingt.

Damit ist ein versehentliches Zurückfallen auf den internen EFACEC-V2-Pfad nach aktuellem Stand nicht die wahrscheinlichste Ursache.

### 4.2 `ReflectionQC45.setConnectorLimitKw()`

Beim Setzen eines DC-Limits wird unter anderem:

- der globale DC-Maximalwert aktualisiert,
- `Satellite.setMaxPower(kw)` gesetzt,
- `DCMaxPowerFixed` aktualisiert,
- für CCS anschließend `sendCcsStart()` ausgelöst.

Das entspricht der im Original-JAR gefundenen V3-Semantik.

### 4.3 `LoadManager`

Der LoadManager führt einen eigenen autoritativen DC-Sollwert (`commandedDcKw`) und stellt ihn wieder her, falls EVCSD intern einen abweichenden Wert meldet.

Die bisherige Analyse zeigt daher **keinen plausiblen Fehler im Regelalgorithmus**, der erklären würde, warum ein korrekt gesetzter Wert von 5 kW fahrzeugseitig zu mehr als 20 kW wird.

Der wahrscheinlichere Fehler liegt weiter hinten im Kommunikationspfad.

---

## 5. Analyse von `mobie-master.bin`

Im ursprünglichen EVCSD-Paket befindet sich:

```text
evcsd/micro_images/mobie-master.bin
```

Größe: **8.960 Byte**.

### 5.1 Mikrocontroller

Die Firmwarestruktur und die AVR-Vektortabelle passen zu einem **ATmega1280**.

Der ATmega1280 besitzt mehrere UARTs, aber keinen integrierten CAN-Controller. Die Kommunikation des untersuchten Master-Images erfolgt daher auf dieser Ebene seriell.

### 5.2 Weiterleitung zum QuickCharge-Pfad

Die Disassemblierung zeigt für den QuickCharge-Gerätepfad eine Weiterleitung des Payloads auf einen separaten USART-Kanal. Der Master interpretiert den darin enthaltenen Leistungswert in diesem alten Firmwarestand nicht als `P/U`-Rechenwert, sondern leitet die Nutzdaten weiter.

Das stützt folgende Architektur:

```text
EVCSD/Linux
   |
   | proprietäres EFACEC Master-Frame
   v
EFACEC Master Controller
   |
   | serieller QuickCharge-Kanal
   v
CCS-Kommunikationscontroller
   |
   | PLC / DIN 70121 / ISO 15118
   v
Fahrzeug
```

### 5.3 Einschränkung

Das im Softwarepaket enthaltene `mobie-master.bin` ist offenbar **älter als der aktuell in der Säule eingesetzte Stand**.

Hinweis darauf: Die alte Firmware kennt auf dem Rückweg ein älteres QuickCharge-Telegrammformat, während die laufende Säule neuere `0x63`-Statusdaten liefert.

Deshalb gilt:

- die Analyse ist ein **starker Architekturhinweis**,
- sie ist **kein vollständiger Beweis** für die aktuell geflashte Master-Firmware.

Für die aktuelle Hardware muss der reale serielle Datenstrom verwendet werden.

---

## 6. Sehr wahrscheinlich: EVAcharge SE als CCS-Controller

Öffentliche Ersatzteilkataloge identifizieren die EFACEC-Teilenummer **20090007** ausdrücklich als:

> EVAcharge SE Board für EFACEC-Elektrofahrzeug-Ladegeräte

Quelle: Costa-Rica-SICOP-Katalog, Einträge zu EFACEC 20090007.

Die EVAcharge SE ist ein CCS-Kommunikationscontroller, der für DIN 70121 und ISO 15118 ausgelegt ist. Herstellerinformationen nennen unter anderem:

- Freescale i.MX287
- Qualcomm QCA7000 Green PHY
- Embedded Linux
- Ethernet
- USB
- CAN/RS232
- Einsatz als EVSE-Kommunikationscontroller

Ältere EVAcharge-SE-Unterlagen nennen außerdem den zugehörigen ECommStack.

Damit ist **sehr wahrscheinlich**, dass der EFACEC-QuickCharge-Pfad am Ende auf einer EVAcharge-SE-/Auronik-/chargebyte-Lösung landet.

### Noch zu bestätigen

Für die konkrete QC45 muss die Platine physisch identifiziert werden:

- A19 / CCS Board
- Teilenummer 20090007
- Boardrevision, z. B. V0R…
- Aufkleber/Firmwareangaben
- Verkabelung zwischen A5/A19/A21

Solange diese Hardwareidentifikation nicht fotografisch bestätigt ist, bleibt dieser Punkt formal „sehr wahrscheinlich“ und nicht „bewiesen“.

---

## 7. Der entscheidende Kona-Befund

Die Publikation **“Intelligent Multi-Vehicle DC/DC Charging Station Powered by a Trolley Bus Catenary Grid”**, Energies 2021, 14, 8399, untersucht Lastmanagement bei DIN-SPEC-70121-DC-Ladung mit kommerziellen Fahrzeugen.

Dort wird beschrieben:

1. Die Regelung während der aktiven Ladung erfolgt über `CurrentDemandReq` / `CurrentDemandRes`.
2. Die Nachrichten werden ungefähr alle 150–200 ms ausgetauscht.
3. `EVSEMaximumPowerLimit` kann die Fahrzeuganforderung indirekt begrenzen.
4. Einige Serienfahrzeuge ignorieren diese Leistungsgrenze.
5. Als konkretes Beispiel wird **Hyundai Kona (2019)** genannt.
6. Eine direkte Begrenzung über `EVSEMaximumCurrentLimit` wurde von den getesteten Fahrzeugen auch bei dynamischer Änderung eingehalten; als gesonderte Ausnahme wird dort ein VW e-Up bei sehr kleinen Strömen beschrieben.

Damit existiert für genau unser beobachtetes Verhalten ein dokumentierter Präzedenzfall.

---

## 8. CharIN-Anforderung zur Strombegrenzung

Die CharIN-Richtlinie für DC CCS 1.0 beschreibt die Reduzierung des Ladestroms durch die Ladestation ausdrücklich über `EVSEMaximumCurrentLimit` in `CurrentDemandRes`.

Sinngemäß fordert die Guideline:

- Das CCS-Fahrzeug soll `EVSEMaximumCurrentLimit` befolgen.
- Reduziert die EVSE diesen Wert, muss das Fahrzeug seinen Zielstrom innerhalb von etwa 1 s entsprechend anpassen.
- Die Funktion ist unter anderem für Leistungsreduzierung und geteilte Leistung zwischen mehreren Ladeabgängen vorgesehen.

Das ist funktional genau das, was unser Peak-Shaving benötigt.

---

## 9. Wahrscheinlichste Fehlerursache

Die derzeit plausibelste Kette lautet:

```text
LoadManager: 5 kW
      |
      v
ReflectionQC45 / EVCSD
Satellite.maxPower = 5
      |
      v
EFACEC V3 QuickCharge
63 .. 05 .. ..
      |
      v
CCS-Controller / EVAcharge SE
      |
      +--> EVSEMaximumPowerLimit = 5 kW     vermutlich korrekt
      |
      +--> EVSEMaximumCurrentLimit = 125 A  vermutlich statisch/falsch
      |
      v
Hyundai Kona
```

Wenn der Kona `EVSEMaximumPowerLimit` ignoriert, aber die hohe statische Stromgrenze sieht, darf er aus seiner Sicht weiterhin einen deutlich höheren Strom anfordern.

Der EVSE-Leistungsteil folgt dann dieser Fahrzeuganforderung, solange keine andere Schutzgrenze eingreift.

Das würde gleichzeitig erklären:

- warum EVCSD weiterhin 5–6 kW als Sollwert meldet,
- warum der reale CCS-Strom trotzdem hochläuft,
- warum Änderungen an `quickChargeMaxCurrent` im Java-Objekt keine Wirkung haben,
- warum andere Fahrzeuge den Fehler möglicherweise nicht zeigen,
- warum der Failback trotz scheinbar kleinem EVCSD-Leistungslimit auslösen kann.

---

## 10. Ziel für die Korrektur

Die Sollleistung muss auf CCS-HLC-Ebene zusätzlich in eine dynamische Stromgrenze umgesetzt werden.

Grundsätzlich:

```text
I_max = P_limit / U_present
```

Beispiel:

```text
P_limit = 5.000 W
U_present = 365 V
I_max = 13,70 A
```

Der genaue zu übertragende Wert hängt von Skalierung und Datentyp des EVAcharge-/ECommStack-Interfaces ab.

Wichtig:

- **Nicht** 13 oder 14 A in Byte 2 des EFACEC-V3-START-Pakets schreiben.
- Dort bleibt der Wert in **kW**.
- Die Stromumrechnung gehört **hinter** diese Schnittstelle, auf dem CCS-Controller bzw. dessen Adapter.

Für ein hartes Leistungsmaximum sollte bei ganzzahliger Stromauflösung konservativ gerundet werden. Bei 365 V wären 13 A ca. 4,75 kW und 14 A ca. 5,11 kW.

---

## 11. Nächste Verifikation

### Schritt 1: Aktuelles V3-Paket physisch bestätigen

Mit dem aktuellen `native-integration`-Stand:

1. Kona anschließen.
2. DC-Limit fest auf 5 kW setzen.
3. Ramp-up möglichst unterbinden bzw. Sollwert konstant halten.
4. `CcsRawTracerV2` aktivieren.
5. TX-Daten des realen seriellen Pfads erfassen.

Erwartet wird ein V3-START mit `05` als Leistungsbyte, z. B.:

```text
63 02 05 xx xx
```

oder entsprechend mit gesetztem Loginbit.

Wenn dieses Paket auf der physischen Leitung sicher nachgewiesen ist und der Kona anschließend trotzdem deutlich mehr als 5 kW zieht, ist die Java-/EVCSD-Seite als Hauptursache praktisch ausgeschlossen.

### Schritt 2: A19 identifizieren

Benötigt werden scharfe Fotos von:

- kompletter A19-Platine,
- Teilenummer/Aufkleber,
- Boardrevision,
- Steckverbindern,
- Verbindung zu A5 und A21.

### Schritt 3: Schnittstelle zum EVAcharge-Controller bestimmen

Zu klären:

- seriell, CAN oder kundenspezifischer Adapter,
- welches Protokoll auf dieser Verbindung läuft,
- ob `EVSEMaxCurrentLimit` bereits als getrenntes Feld existiert,
- ob das EFACEC-Adapterprogramm nur `EVSEMaxPowerLimit` setzt.

### Schritt 4: Current-Limit dynamisch setzen

Zielverhalten während aktiver Ladung:

```text
powerLimitKw = LoadManager-Sollwert
presentVoltage = aktuelle CCS-Spannung
currentLimitA = powerLimitKw * 1000 / presentVoltage
```

Danach muss der entsprechende Stromgrenzwert im CCS-Stack laufend aktualisiert werden, sodass die `CurrentDemandRes`-Antwort den reduzierten `EVSEMaximumCurrentLimit` enthält.

Die Aktualisierung muss schnell genug für Lastmanagement sein; der HLC-Dialog selbst läuft deutlich schneller als unser 1-s-LoadManager.

---

## 12. Was aktuell nicht weiterverfolgt werden sollte

### Ampere in EFACEC-V3-Byte 2 schreiben

**Verworfen.** Byte 2 ist beim CCS-V3-START `maxPower` in kW.

### `quickChargeMaxCurrent` in EVCSD ändern

Allein nicht ausreichend. Der originale V3-CCS-Serializer verwendet dieses Feld nicht für das START-Paket.

### Auf EFACEC CCS V2 zurückschalten

Keine sinnvolle Lösung. Der analysierte V2-START transportiert keinen entsprechenden dynamischen Leistungswert.

### LoadManager neu schreiben

Derzeit nicht begründet. Der LoadManager setzt die gewünschten kW-Werte. Der Fehler tritt danach in der CCS-Kette auf.

---

## 13. Offene Punkte

- [ ] Aktuelles TX-Paket mit korrigiertem V3-Branch physisch als `... 05 ...` bei 5 kW bestätigen.
- [ ] A19-Platine als EFACEC 20090007 / EVAcharge SE identifizieren.
- [ ] Boardrevision und Firmwarestand erfassen.
- [ ] A5/A19/A21-Verkabelung dokumentieren.
- [ ] Protokoll zwischen EFACEC-Master und CCS-Controller rekonstruieren.
- [ ] Prüfen, welchen Wert der Controller aktuell als `EVSEMaximumCurrentLimit` sendet.
- [ ] Dynamische Stromgrenze implementieren.
- [ ] Kona erneut mit 5, 10, 15 und 20 kW Sollwert testen.
- [ ] Gegenprobe mit Fahrzeugen durchführen, die die Leistungsbegrenzung bereits korrekt befolgen.

---

## 14. Quellen

### QC45 / EFACEC / Hardware

- SICOP-Katalog: EFACEC 20090007 wird als **EVAcharge SE Board** geführt:  
  https://www.sicop.go.cr/moduloBid/common/co/EpSearchItemDetail.jsp?page_no=142422
- chargebyte EVAcharge SE Produktseite:  
  https://chargebyte.com/products/controllers-modules/evse-controllers/evacharge-se
- EVAcharge SE BSP – Netzwerk/Interfaces:  
  https://evacharge-se-bsp.readthedocs.io/en/latest/networking.html

### CCS / DIN SPEC 70121

- CharIN – Technical Details CCS:  
  https://www.charin.global/technology/technical-details-ccs-basic/
- CharIN Guideline for DC CCS 1.0 Implementation:  
  https://www.charin.global/media/pages/home/technical-details-ccs-basic/b70e776669-1645622498/ccs_guideline_v1p6.pdf

### Hyundai Kona / Power- vs. Current-Limit

- M. Weisbach et al., **Intelligent Multi-Vehicle DC/DC Charging Station Powered by a Trolley Bus Catenary Grid**, Energies 2021, 14, 8399:  
  https://www.mdpi.com/1996-1073/14/24/8399

---

## 15. Fazit

Der bisherige Befund spricht stark dafür, dass die Leistungsbegrenzung **bis zur EFACEC-V3-QuickCharge-Schnittstelle korrekt** ist. Der Kona-spezifische Fehler entsteht sehr wahrscheinlich danach: auf der CCS-HLC-Ebene wird offenbar nur eine Leistungsgrenze wirksam kommuniziert, während die direkte Stromgrenze zu hoch bleibt.

Der nächste sinnvolle Eingriff ist deshalb nicht ein weiterer Umbau des LoadManagers, sondern die Untersuchung und gegebenenfalls Anpassung des CCS-Controllers beziehungsweise seines EFACEC-Adapters, sodass `EVSEMaximumCurrentLimit` dynamisch aus dem aktuellen Leistungssollwert und der Batteriespannung gebildet wird.
