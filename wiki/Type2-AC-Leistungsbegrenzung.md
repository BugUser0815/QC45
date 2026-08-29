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

- `> 0 kW`: Leistungsgrenze per `ENERGY` bzw. beim Wiederanlauf per `START_CHARGE`
- `0 kW`: **nicht** als normales `maxPower=0` behandeln, sondern `SUSPEND_CHARGE` senden

## Implementierung

`AcPowerLimitTransport` läuft innerhalb der EVCSD-Webapp und beobachtet den wirksamen AC-Sollwert des `ChargingLimitCoordinator`.

### Freigabe 0 kW

Bei aktiver Type2-Sitzung und effektivem Sollwert `0 kW` wird sofort ein natives MobiBus-`SUSPEND_CHARGE` an Satellit 3 gesendet. Solange die Freigabe 0 bleibt, wird der Suspend-Befehl jede Sekunde erneut bestätigt.

Damit können weder ein alter Java-Cached-Wert noch ein späterer Legacy-EVCSD-Schreibzugriff die Nullfreigabe nur scheinbar erfüllen.

### Positive Freigabe

Ändert sich ein positiver Sollwert während einer laufenden Sitzung, sendet der Transport sofort ein `ENERGY`-Paket mit dem Sollwert in 0,1-kW-Einheiten.

Nach einer echten Suspend-Phase wird mit einem nativen `START_CHARGE` wieder freigegeben. Dabei wird derselbe Kredit-/Schlüsselmechanismus wie im Original-EVCSD verwendet; die bestehende Transaktion wird nicht neu angelegt und die Sitzungsenergie wird nicht zurückgesetzt.

### Aktuelle AC-Leistung

Das alte EVCSD lässt `SatelliteInfo.power` beim normalen Type2-MobiBus-Pfad häufig auf `0`, obwohl die Energiezählung weiterläuft. Deshalb wird die aktuelle AC-Leistung zusätzlich aus der Änderung von `getCurrentEnergy()` über ein kurzes Zeitfenster berechnet.

Der daraus abgeleitete kW-Wert wird in das vorhandene `infoState.power` gespiegelt. Dadurch sehen anschließend auch:

- der Modbus-Server,
- der lokale Lademonitor,
- der `ChargingLimitGuard`

einen brauchbaren aktuellen AC-Leistungswert statt dauerhaft `0 kW` oder eines Durchschnittswerts über die gesamte Sitzung.

## Sicherheitsverhalten

Der bestehende `ChargingLimitGuard` bleibt unverändert aktiv. Er reassertiert Nullfreigaben ohnehin regelmäßig. Durch die neue Type2-Leistungsermittlung kann er jetzt zusätzlich erkennen, wenn trotz `0 kW` physisch noch Leistung fließt, und bei anhaltender Abweichung den vorhandenen `LIMIT_MISMATCH`-Hard-Stop auslösen.

Beim Beenden bzw. Neuladen der nativen Integration wird eine noch aktive AC-Sitzung vorsorglich per `SUSPEND_CHARGE` pausiert.

## Erwartete Logs

Beim Start:

```text
[QC45] AC MobiBus power-limit transport started zero=SUSPEND_CHARGE positive=ENERGY resume=START_CHARGE power=energy-delta
```

Bei 0-kW-Freigabe:

```text
[QC45] AC MobiBus SUSPEND target=0kW actual=11kW reassert=false
```

Bei erneuter positiver Freigabe:

```text
[QC45] AC MobiBus RESUME target=11kW packet=110 deci-kW
```

Bei einer Änderung einer positiven Grenze:

```text
[QC45] AC MobiBus LIMIT target=16kW packet=160 deci-kW
```

## Betroffene Dateien

- `native-integration/src/main/java/de/rothner/qc45/AcPowerLimitTransport.java`
- `native-integration/src/main/java/de/rothner/qc45/BootstrapListener.java`
- Original-EVCSD als Reverse-Engineering-Referenz: `SatelliteModule`, Normal-AC-`ChargingState`, `MobibusSerializer`, `MobibusProtocol`
