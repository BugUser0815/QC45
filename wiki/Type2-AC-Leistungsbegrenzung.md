# Type2 / AC-Leistungsbegrenzung

## Problem

Beim Type2-Anschluss konnte die native Integration intern korrekt `0 kW` als wirksame Freigabe berechnen, während ein angeschlossenes Fahrzeug physisch weiterlud. `Configuration.maxPowerAC=0` und `SatelliteModule.setMaxPower(0)` ändern zunächst nur Java-seitige Werte; sie sind für sich kein physischer Pause-Befehl an den AC-Satelliten.

Das führte zu einem gefährlichen Widerspruch:

```text
LoadManager / UI:  FREIGABE 0 kW
Java-Satellit:     maxPower 0 kW
Type2-Fahrzeug:    lädt weiter
```

## Reverse Engineering des Original-EVCSD

Untersucht wurde das originale `evcsd.jar` der QC45.

### Positive Leistungsgrenzen

Für den normalen AC-Satelliten verwendet EVCSD das MobiBus-Protokoll. Bei aktivierter AC-Load-Balance-Funktion wird die AC-Leistungsgrenze in zwei Pakettypen übertragen:

- `START_CHARGE`
- `ENERGY`

Der Wert wird als `maxPower` mit Faktor 10 serialisiert:

```text
11 kW -> maxPower 110
22 kW -> maxPower 220
43 kW -> maxPower 430
```

`SatelliteModule.setMaxPower()` allein sendet dagegen kein Paket; die Methode ändert nur `satelliteMaxPower` im Java-Objekt.

### Warum 0 kW nicht als normale Leistungsgrenze taugt

Im originalen Normal-AC-`ChargingState` ist für den Load-Shed-Pfad ausdrücklich folgende Sonderbehandlung vorhanden:

```text
berechnete Leistung == 0  ->  auf 1 setzen
```

Der Originalcode vermeidet damit selbst eine normale `0`-Leistungsfreigabe. Gleichzeitig besitzt das MobiBus-Protokoll einen eigenen Pakettyp:

```text
SUSPEND_CHARGE
```

Daraus folgt für die native Integration:

- `> 0 kW`: Leistungsgrenze per `ENERGY`
- logisch `0 kW`: während einer autorisierten Sitzung physisch `5 kW`
  Notladen; der Hard-Trip beendet die Transaktion unabhängig per RemoteStop

## Implementierung

`AcPowerLimitTransport` läuft innerhalb der EVCSD-Webapp und beobachtet den wirksamen AC-Sollwert des `ChargingLimitCoordinator`.

### Logische Freigabe 0 kW

Bei aktiver Type2-Sitzung wird ein logischer Sollwert von `0 kW` als `5 kW`
Notladen übertragen. Das entspricht der DC-Regelung und hält für ein
dreiphasiges Fahrzeug mindestens 6 A je Phase bereit. Netzschutz-Hard-Trips
enden die Sitzung per RemoteStop. Beim Beenden der Integration bleibt
`SUSPEND_CHARGE` als zusätzlicher Abschaltpfad erhalten.

### Positive Freigabe

Ändert sich ein positiver Sollwert während einer laufenden Sitzung, sendet der Transport ein `ENERGY`-Paket mit dem Sollwert in 0,1-kW-Einheiten.

Beim Sitzungsstart enthält bereits das originale `START_CHARGE`-Telegramm die
vorab gesetzte Leistungsgrenze. Die Integration lässt diesem nativen Handshake
fünf Sekunden Zeit und sendet in diesem Fenster kein zusätzliches `ENERGY`-
Telegramm. Das verhindert eine parallele MobiBus-Anfrage während der
Schütz-/CP-Freigabe. Nach dem Startfenster werden geänderte Sollwerte weiterhin
sofort per `ENERGY` übertragen.

### Aktuelle AC-Leistung

Das alte EVCSD lässt `SatelliteInfo.power` beim normalen Type2-MobiBus-Pfad häufig auf `0`, obwohl die Energiezählung weiterläuft. Deshalb wird die aktuelle AC-Leistung zusätzlich aus der Änderung von `getCurrentEnergy()` über ein kurzes Zeitfenster berechnet.

Der daraus abgeleitete kW-Wert wird in das vorhandene `infoState.power` gespiegelt. Dadurch sehen anschließend auch:

- der Modbus-Server,
- der lokale Lademonitor,
- der `ChargingLimitGuard`

einen brauchbaren aktuellen AC-Leistungswert statt dauerhaft `0 kW` oder eines Durchschnittswerts über die gesamte Sitzung.

## Sicherheitsverhalten

Der bestehende `ChargingLimitGuard` bleibt aktiv. Durch die Type2-Leistungsermittlung kann er erkennen, wenn die gemessene Leistung vom wirksamen Notlade- oder Regelsollwert abweicht, und bei anhaltender Abweichung den vorhandenen `LIMIT_MISMATCH`-Hard-Stop auslösen.

Beim Beenden bzw. Neuladen der nativen Integration wird eine noch aktive AC-Sitzung vorsorglich per `SUSPEND_CHARGE` pausiert.

## Erwartete Logs

Beim Start:

```text
[QC45] AC MobiBus power-limit transport started logical-zero=5kW limits=ENERGY hard-trip=RemoteStop power=energy-delta
```

Beim Erkennen und während einer AC-Sitzung werden zusätzlich der native
Transaktionszustand, Kabel-/Ladestatus, Energiezähler und `acDTC` protokolliert.
Damit ist ein erneuter Abbruch ohne separates Debug-Build auswertbar.

Beim nativen Sitzungsstart:

```text
[QC45] AC session started native-start-limit=5kW requested=5kW energy-update-deferred=5000ms ...
```

Bei einer Änderung einer positiven Grenze:

```text
[QC45] AC MobiBus LIMIT target=16kW packet=160 deci-kW
```

## Betroffene Dateien

- `native-integration/src/main/java/de/rothner/qc45/AcPowerLimitTransport.java`
- `native-integration/src/main/java/de/rothner/qc45/BootstrapListener.java`
- Original-EVCSD als Reverse-Engineering-Referenz: `SatelliteModule`, Normal-AC-`ChargingState`, `MobibusSerializer`, `MobibusProtocol`
