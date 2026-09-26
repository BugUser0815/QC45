# Type2 / AC-Leistungsbegrenzung

## Aktueller Stand (`native-integration`)

Die unten beschriebene direkte MobiBus-Ansteuerung ist ein **früherer
Versuchsstand**. Der aktuelle `BootstrapListener` startet
`AcFixedPowerBridge` und `AcPowerTelemetry`; `AcPowerLimitTransport` sendet
keine Pakete mehr. Die originale AC-Lastverteilung wird zur Laufzeit
deaktiviert. Der KSEM-Regler schreibt das logische kW-Ziel auf den
AC-Satelliten. `AcFixedPowerBridge` wandelt es in einen dreiphasigen
Pilotstrom um und schreibt `ACMaxPowerFixed`; EFACEC sendet diesen Wert mit
seinen eigenen `START_CHARGE`- und periodischen `ENERGY`-Paketen.

Die Integration nimmt derzeit an, dass der Type2-Payload **0,1 A** bedeutet:
5 kW werden zu 8 A beziehungsweise Payload 80; 11 kW zu 16 A
beziehungsweise Payload 160. **Diese Einheit ist für die AC-Platine nicht
verifiziert.** Der originale Java-Code multipliziert die Konfiguration nur
mit zehn und serialisiert anschließend einen 16-Bit-Wert. `maxPowerAC` ist nur ein konservativ
mitgeführter Fallback und wird vom festen AC-Pfad nicht gelesen.
`DCMaxPowerFixed` bleibt als Selektor positiv; die eigenständige originale
DC-Lastverteilung bleibt aus. Bei logisch 0 kW verlangt die Integration
weiterhin einen 5-kW-Notladewert; dass die Platine diesen tatsächlich
umsetzt, ist nach den fehlgeschlagenen Versuchen gerade nicht belegt.
Ein Hard-Trip beendet die Transaktion per RemoteStop.

### Befund vom 25. September 2026

Das originale `qc45.zip` enthält `mainConfig.properties` mit
`AC.load.balance.enabled=false`, `AC.maxPower.fixed=35` und
`DC.maxPower.fixed=35`. In `evcsd.jar` (Build 57) nutzt
`SatelliteModule.sendNormalChargeStart()` für Type2 `ACMaxPowerFixed * 10`,
**wenn beide Fixed-Werte positiv sind**. Bei aktivierter originaler
AC-Lastverteilung nutzt es dagegen
`maxPowerAC / (getSatsInCharge() + 1) * 10`. Die periodische
`getEnergy()`-Abfrage nutzt beim Fixed-Weg ebenfalls `ACMaxPowerFixed * 10`,
bei AC-Lastverteilung jedoch `maxPowerAC / getSatsInCharge() * 10`,
ohne Schutz gegen null. `getSatsInCharge()` zählt den nativen
`normalStatus.functional == CHARGING` aller Satelliten, nicht die
Transaktionen des LoadManagers. Bei null ergibt die Java-Konvertierung
`Integer.MAX_VALUE`, wovon nur die unteren 16 Bit in der MobiBus-Nachricht
landen. Der rohe Wert `65535` kann keine verlässliche AC-Grenze sein.

Ein älterer i3-Ladevorgang mit aktiviertem AC-Lastverteilungsweg ließ
den Energiezähler von 0 auf über 1200 Wh steigen und lieferte etwa 11 kW,
auch wenn das Java-seitige Limit zeitweise 5 kW war. Die aktuellen
Versuche mit Fixed-Weg und Wert 8 (START/ENERGY-Payload 80) liefern
dagegen 0 Wh. Das zeigt einen Zusammenhang, aber noch nicht, ob die
Platine den kleinen Zahlenwert verwirft, welcher Strombereich gültig ist
oder ob ein weiterer CP/Schütz-Fehler vorliegt. Der in der Integration
gelesene `normalStatus` hat bei den aktuellen Versuchen
`statusResOK=false` und ist deshalb kein sicherer Beweis für einen
gültigen Status der AC-Platine.

`AcNativeLimitTrace` protokolliert für den nächsten Versuch am Sitzungsstart
und im Startfenster beide nach Originalcode errechneten Rohwerte und den
aktuellen Divisor. Diese Spur sendet keine MobiBus-Befehle und ändert die
Regelung nicht. Vor einer Verhaltensänderung müssen die tatsächlich
akzeptierten Rohwerte und der Übergang am AC-Ausgang kontrolliert verglichen
werden; ein Rückschalten auf den früheren Weg würde die nachgewiesene
Überladung bei Freigabe 0 erneut ermöglichen.

### Versuch vom 26. September 2026, etwa 10:47 Uhr MESZ

Die neue Diagnoseversion lief seit 09:42 Uhr MESZ. Beim BMW-i3-Start war
`acBalance=false`, `chargingCount=1`, `acFixed=8`, `dcFixed=5` und
`maxPowerAC=8`. Der originale Fixed-Weg berechnet dadurch für START und
ENERGY durchgehend den Rohwert `80`. Der kritische Divisor null trat in
diesem Versuch nicht auf. Das ist eine Rekonstruktion aus dem originalen
Java-Code und den Laufzeitfeldern, kein Mitschnitt der seriellen Leitung.

Der KSEM meldete vor dem Start etwa 6,4 A Netzbezug bei 33,4 A Zielwert;
der LoadManager gab 5 kW frei. Von 10:47:17 bis 10:47:33 Uhr blieb
der Energiezähler bei 0 Wh und die Leistung bei 0 kW. EVCSD wechselte
dann von `ChargingState` nach `InUseAfterChargingState` und sendete
anschließend STOP. Kurz vor dem Start erschien AC-DTC 9400, nach dem
Sitzungsende 9100. Die Diagnose liefert für `normalStatus` weiterhin
`statusResOK=false`; diese Codes und die Nullwerte der Board-Spannungsfelder
beweisen deshalb für sich keine bestimmte physische Fehlerursache.

Damit ist ein KSEM-bedingtes Zurücknehmen der Freigabe und der
Null-Divisor im aktuellen Fixed-Pfad ausgeschlossen. Zu prüfen bleibt,
ob die AC-Platine den Rohwert 80 als gültige Pilotgrenze akzeptiert und
welcher CP-Zustand den Schützabfall auslöst. Ein weiterer identischer
Startversuch ohne zusätzliche Messung bringt dafür keine neue Information.

## Historischer Versuchsstand

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

Der Wert wird als `maxPower` mit Faktor 10 serialisiert. Die folgende
kW-Zuordnung war eine damalige Annahme und ist für die Platine nicht belegt:

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
