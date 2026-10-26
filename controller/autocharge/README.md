# AutoCharge für die interne CCS-Flotte

Stand: 26.10.2026. Implementierung für einen kontrollierten Fahrzeugtest;
noch keine Abnahme auf der echten QC45 oder am produktiven OCPP-Backend.

## Freigabe und Buchung

Jedes Flottenfahrzeug erhält eine eigene OCPP-`idTag` im Backend. Eine lokale
Datei ordnet seine EVCCID dieser Backend-Kennung zu. Die Zuordnung ist keine
lokale Ladefreigabe: **Jeder neue Versuch benötigt ein frisches, erfolgreiches
OCPP-`Authorize`**. Ablehnung, Timeout, Verbindungsfehler und ungültige Antworten
geben keine automatische Ladung frei. Autorisierungen werden nicht gecacht.

| Schritt | Ausführung |
|---|---|
| Fahrzeug erkennen | Python-Beobachter liest auf EVAcharge SE passiv die EVCCID aus einer neuen DIN-`SessionSetupReq` auf `qca0` |
| Fahrzeug zuordnen | Geode sucht die EVCCID in der konfigurierten Flottendatei |
| Autorisieren | Bestehende OCPP-1.6-WSS-Verbindung sendet `Authorize` mit der individuellen Fahrzeug-`idTag` |
| Starten | Erst nach `Accepted` ruft die Integration den vorhandenen nativen RemoteStart für CCS-Connector 2 auf |
| Transaktion anlegen | Original-EVCSD sendet `StartTransaction`; die bestehende SOAP-/JSON-Brücke reicht Kennung, Zeit und Zählerstand ans Backend weiter |
| Energie und Ende buchen | Original-EVCSD und die vorhandene Brücke senden `MeterValues` und `StopTransaction` mit Backend-Transaktionsnummer |

Die neue Funktion erzeugt **keine zweite Transaktion** und ersetzt keine
Zählerstände durch berechnete Werte. Ein abgelehnter Transaktionsstart oder
eine nach 15 Sekunden fehlende Backend-Bestätigung löst den Stopp der eigenen
nativen CCS-Session aus. Der Stopp wird bis zur beobachteten Inaktivität
wiederholt. Fremde Karten-/Remote-Sessions werden anhand des gespeicherten
Start-Tags und des aktuellen nativen Benutzers geschützt. Ein Backend-Ausfall
während einer bereits bestätigten Transaktion bleibt im vorhandenen nativen
OCPP-Lebenszyklus; diese Erweiterung ist keine neue Offline-Abrechnungsqueue.

Für die Flottenauswertung müssen die einzelnen Backend-Kennungen dem richtigen
Fahrzeug bzw. Firmenkonto zugeordnet sein. Die spätere Vollständigkeit von
Zeit, Zählerständen und Energie ist Bestandteil des realen Abnahmetests.

## Grenzen und Sitzungszuordnung

- Unterstützt wird der auf dieser QC45 beobachtete **DIN-70121-CCS-Dialog**.
  Type 2 ohne HLC, CHAdeMO, ISO-15118-EXI und TLS werden nicht ausgelesen.
- Der Beobachter braucht einen neuen TCP-SYN und anschließend SAP-Anfrage und
  SessionSetup. Er liest keine alte Bildschirmdatei und sucht nicht nach
  Bytefolgen in beliebigen Paketen. Signed-/Notification-Header und
  Sitzungswiederaufnahme mit einer nichtnull SessionID werden abgewiesen.
- Die Sitzung erhält einen zufälligen Token je TCP-Verbindung. Die V2G-
  SessionID wird nicht als eindeutige Identität verwendet.
- Mehrdeutige parallele Verbindungen, veraltete Beobachtungen, FIN/RST und
  ein Beobachter-Neustart ergeben keine neue Freigabe. Nach Neuverbindung
  zum Geode muss eine neue Fahrzeug-TCP-Verbindung folgen; gegebenenfalls
  Fahrzeug abstecken und neu verbinden.
- Pro Verbindung gibt es höchstens einen Autorisierungsversuch. Nach
  Backend-Ablehnung, Timeout oder manuellem Stopp wird nicht automatisch erneut
  gestartet. Nach Korrektur der Backend-Zuordnung neu verbinden.
- Die EVCCID muss am Fahrzeug über mehrere Plug-ins, Schlaf-/Neustartzyklen
  und später erneut auf Stabilität geprüft werden. Eine EVCCID ist kopierbar
  und kein kryptografischer Fahrzeugnachweis. Dies ist MAC-/EVCCID-basiertes
  AutoCharge für eine bewusst ausgewählte interne Flotte.
- KSEM-/SLS-Schutz, Leistungsregelung, evcc-Permit und die normalen
  Fahrzeug-Sicherheitsprüfungen bleiben für jede Ladung erforderlich.
  AutoCharge erteilt ausschließlich die Autorisierung, keine zusätzliche
  Leistungsfreigabe.

Der ursprüngliche Ladecontroller und der separate Leistungsgrenzen-Fix werden
nicht geändert. Es werden keine CAN-/PLC-Steuertelegramme gesendet.

## 1. Fahrzeugkennungen ermitteln und im Backend anlegen

Auf dem EVAcharge zunächst den Beobachter **ohne Netzwerkverbindung und ohne
Autorisierungsfunktion** starten:

```sh
python /pfad/zum/autocharge_agent.py --print-evccid
```

Danach Fahrzeug verbinden und den ausgegebenen `EVCCID=...`-Wert sichern.
Mit Strg-C beenden. Vorgang nach Fahrzeugschlaf und erneutem Verbinden
wiederholen. Die vollständige Fahrzeugkennung wird nur in diesem ausdrücklich
gewählten Erfassungsmodus ausgegeben.

Im OCPP-Backend pro Fahrzeug eine individuelle Kennung registrieren und dem
Firmenkonto/Fahrzeug zuweisen. Beispiel ausschließlich mit synthetischer
Kennung: `EV020000000001`. Das Backend muss die Kennung für diese Station
autorisieren und seine Transaktionen dem gewünschten Konto zuordnen.
Falls das Backend andere Kartenformate verlangt, kann die rechte Seite der
Zuordnung eine passende vorhandene **fahrzeugspezifische** Kennung enthalten.

Auf dem Geode eine private Datei
`/home/mobie/evcsd/qc45-autocharge-vehicles.properties` anlegen:

```properties
# Synthetisches Beispiel, durch tatsächliche Fahrzeug-/Backend-Zuordnung ersetzen.
020000000001=EV020000000001
```

Jede EVCCID und jede `idTag` darf nur einmal vorkommen. Die Backend-Kennung
darf 1–20 Zeichen aus Buchstaben, Ziffern, `_` und `-` enthalten. Die Datei
wird beim Start geladen; Änderungen benötigen einen Integrationsneustart.
Fahrzeuglisten, Schlüssel und produktive Konfiguration gehören nicht ins Repo.

## 2. Beobachterverbindung einrichten

Der Beobachter verbindet sich per TCP zum Geode. Der Receiver akzeptiert nur
die konfigurierte Quelladresse und authentisiert jede Verbindung mit einer
frischen 256-Bit-Challenge und HMAC-SHA256. Der Schlüssel wird nicht übertragen.
Dieser Kanal ist nicht verschlüsselt; er ist für die interne Controller-
Verbindung vorgesehen. Es gibt keine HTTP-Start-API und keine Shell-Kommandos
aus eingehenden Beobachtungen.

Auf einem Administrationsrechner einen Schlüssel erzeugen:

```sh
umask 077
python3 -c 'import os, binascii; print(binascii.hexlify(os.urandom(32)).decode("ascii"))' > observer.key
```

Die Datei geschützt auf beide Boards kopieren:

- Geode: `/home/mobie/evcsd/qc45-autocharge-observer.key`, für den
  EVCSD-Prozess lesbar, für andere Nutzer unlesbar.
- EVAcharge: zunächst in ein Vorbereitungsverzeichnis zusammen mit `agent.json`;
  der Installer kopiert beides nach `/home/qc45-autocharge/` (root, Modus 0700/0600).

`agent.example.json` als `agent.json` kopieren. `host` auf die vom Controller
erreichbare interne Geode-Adresse setzen. `key_file` bleibt
`/home/qc45-autocharge/observer.key`.

Die Verbindung und Geode-Zusatzadresse müssen auch nach einem Neustart
erreichbar sein. Eine zuvor mit `ifconfig` ergänzte temporäre Adresse reicht
dafür nicht aus. Vor der Abnahme die bestehende Netzwerkkonfiguration beider
Boards prüfen und die gewählte interne Route/Adresse dauerhaft hinterlegen;
dieser Installer verändert keine Netzwerkschnittstelle.

## 3. Geode-Integration konfigurieren und ausliefern

Die neue `qc45-integration.jar` mit dem vorhandenen Deployment-Verfahren
ausliefern. Die Funktion ist standardmäßig aus. Erst mit vorbereiteten
Backend-Kennungen und Dateien in der privaten Integrationskonfiguration
aktivieren:

```properties
autocharge.enabled=true
autocharge.bindAddress=<ERREICHBARE_INTERNE_GEODE_ADRESSE>
autocharge.port=15119
autocharge.allowedPeer=<INTERNE_EVAcharge_QUELLADRESSE>
autocharge.keyFile=/home/mobie/evcsd/qc45-autocharge-observer.key
autocharge.vehiclesFile=/home/mobie/evcsd/qc45-autocharge-vehicles.properties
autocharge.authorizeTimeoutMs=10000
```

Beide OCPP-Brücken, der CCS-V3-Pfad und die vorhandenen Schutzkomponenten
müssen verfügbar sein. Der Receiver bindet beim Start; ungültige Konfiguration
deaktiviert AutoCharge und wird im Integrationslog sichtbar. Normale Karten-
und Backend-Freigaben bleiben verfügbar. Ein solcher Startfehler gilt für die
Deployment-Gesundheitsprüfung als nicht betriebsbereit.

## 4. EVAcharge-Beobachter installieren

Den Ordner `controller/autocharge` auf den EVAcharge übertragen und dort als
root ausführen. Das Vorbereitungsverzeichnis muss `agent.json` und den
gemeinsamen `observer.key` enthalten:

```sh
sh /pfad/zum/autocharge/install.sh /absolutes/vorbereitungsverzeichnis
/etc/init.d/qc45-autocharge-agent start
/etc/init.d/qc45-autocharge-agent status
```

Python 2.7, `/usr/sbin/tcpdump` und die vorhandene `qca0`-Schnittstelle werden
verwendet. Es wird kein Paket nachinstalliert. Ein unabhängiger SysV-Dienst
startet den Beobachter nach Board-Neustart und nach einem Capture-Prozessfehler
erneut. Er überschreibt weder `/home/EVAStart` noch den Leistungsgrenzen-Fix.
`status` bestätigt den Supervisor; für die tatsächliche Verbindung müssen auch
Geode-Log und der reale Fahrzeugtest geprüft werden.

## Abnahme vor unbeaufsichtigtem Flottenbetrieb

1. Im Geode-Log `AutoCharge fleet receiver started` prüfen. Geode-Uhrzeit und
   Backend-Verbindung kontrollieren. Fahrzeug frisch verbinden.
2. Registriertes Fahrzeug: im Backend `Authorize` mit seiner Kennung und
   `Accepted`, anschließend genau einen akzeptierten `StartTransaction` für
   Connector 2 mit **derselben Kennung** nachweisen.
3. `MeterValues` mit Energie/Zeit erfassen und ein reguläres Ladeende prüfen:
   `StopTransaction`, passende Transaktionsnummer und plausibler Endzählerstand.
4. Nicht registriertes Fahrzeug und im Backend gesperrte Kennung: keine
   automatische Ladung. Normale autorisierte Kartenfreigabe separat testen.
5. Backend vor Start nicht erreichbar, abstecken während `Authorize`, Neustart
   von Beobachter/Geode und manueller Backend-Stopp: keine alte Kennung darf
   neu starten. Backend-Transaktionsablehnung/-Timeout muss zum Stopp führen.
6. Nach regulärem Neustart beider Boards Erreichbarkeit, Dienst und erneute
   Autorisierung mit vollständiger Backend-Transaktion prüfen.

## Rücknahme

Auf dem Geode `autocharge.enabled=false` setzen und beim nächsten sicheren
Integrationsneustart übernehmen. Auf EVAcharge:

```sh
/etc/init.d/qc45-autocharge-agent stop
update-rc.d -f qc45-autocharge-agent remove
```

Der native Lade-/OCPP-Pfad und der Leistungsgrenzen-Fix bleiben erhalten.

## Entwicklungsprüfung

```sh
cd native-integration
mvn clean package
cd ..
python3 -m unittest discover -s controller/autocharge/tests -v
```

Die EXI-Testdaten sind synthetisch, unabhängig mit EXIficient EXICodec 1.55
erzeugt und wieder dekodiert. Der passive Parser wurde zusätzlich auf beiden
vorliegenden Panda-PCAPs geprüft und erkennt jeweils genau die dort dekodierte
SessionSetup-EVCCID. Das ersetzt den beschriebenen Live-OCPP-Abnahmetest nicht.

Referenzen:

- [EVerest: AutoCharge/EVCCID als Autorisierungstoken](https://everest.github.io/release_2026_02_0/reference/modules/EVSE/EvseManager/autogenerated.html#autocharge-pnc)
- [DIN-EXI-Grammatikreferenz, libcbv2g](https://github.com/EVerest/libcbv2g/blob/main/lib/cbv2g/din/din_msgDefDecoder.c)
- [EXIficient-basierter Referenzcodec](https://github.com/EcoG-io/iso15118)
