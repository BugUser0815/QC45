# Automatisches QC45-UI-Deployment

Der Raspberry prüft im Minutentakt den UI-Quellstand des Branches
`native-integration`. Wenn sich `ui-patch/src` geändert hat, wird die aktive
UI-JAR von der QC45 geladen, die vollständige operative Oberfläche dagegen als
Java-7-Bytecode gebaut und die neue JAR zurück auf die Säule übertragen.

Der Deployer führt dabei keine aus GitHub geladenen Shellskripte aus. Er
extrahiert ausschließlich `ui-patch/src` und verwendet das lokal installierte
Deployskript.

## Sicherheits- und Ausfallverhalten

- SSH ausschließlich zur internen QC45 unter `10.0.0.156`
- eigener RSA-Deployschlüssel; kein Passwort in GitHub oder in der Konfiguration
- `ssh-rsa` wird nur für diese einzelne SSH-Verbindung freigeschaltet
- SHA-256-Prüfung nach der Übertragung
- Sicherung der aktiven JAR vor jedem Austausch
- atomarer Austausch innerhalb von `/home/mobie/evcsd/ui/lib`
- Neustart ausschließlich von `pt.efacec.es.evcsd.ui.Main 5678`
- der vorhandene QC45-UI-Watchdog startet die Oberfläche neu
- automatisches Rollback, wenn der neue UI-Prozess nicht mindestens zehn
  Sekunden stabil bleibt
- die fünf neuesten erfolgreichen Sicherungen bleiben auf der QC45 erhalten

EVCSD/Tomcat, Modbus und die Ladesteuerung werden nicht neu gestartet.

## 1. Voraussetzungen auf dem Raspberry

OpenJDK 21 stellt `java`, `jar` und `javap` bereit. Da dessen `javac` keinen
Java-7-Zielstand mehr erzeugt, lädt der Installer den fest auf Version 3.32.0
gesetzten Eclipse-Compiler direkt aus Maven Central. Die Datei wird vor der
Installation per SHA-256 geprüft. Das fehlerhafte Debian-`ecj`-Startskript
wird nicht verwendet.

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk-headless curl
```

Der Installer prüft anschließend durch eine Testkompilierung, ob der gepinnte
Compiler tatsächlich Java-7-Bytecode (Class-Major-Version 51) für die alte
QC45-Laufzeit erzeugt.

## 2. SSH-Schlüssel für die alte QC45

Die QC45 bietet nur alte SSH-Algorithmen an. Der folgende Schlüssel wird
ausschließlich für das Deployment verwendet:

```bash
mkdir -p ~/.ssh
chmod 700 ~/.ssh
ssh-keygen -t rsa -b 3072 -m PEM -f ~/.ssh/qc45_deploy -C qc45-ui-deployer
```

Zuerst den Hostschlüssel interaktiv prüfen und übernehmen:

```bash
ssh -o HostKeyAlgorithms=+ssh-rsa root@10.0.0.156 'echo QC45-SSH-OK'
```

Danach den Deployschlüssel installieren:

```bash
ssh-copy-id \
  -i ~/.ssh/qc45_deploy.pub \
  -o HostKeyAlgorithms=+ssh-rsa \
  -o PubkeyAcceptedAlgorithms=+ssh-rsa \
  root@10.0.0.156
```

Passwortlosen Zugriff testen:

```bash
ssh \
  -i ~/.ssh/qc45_deploy \
  -o BatchMode=yes \
  -o HostKeyAlgorithms=+ssh-rsa \
  -o PubkeyAcceptedAlgorithms=+ssh-rsa \
  root@10.0.0.156 \
  'test -r /home/mobie/evcsd/ui/lib/evcsdUI-v4_EFACEC-ALL_IN_ONE_GENERIC.jar && command -v sha256sum'
```

## 3. Deployer installieren

```bash
cd ~/Development
git clone --branch native-integration https://github.com/BugUser0815/QC45.git
cd QC45/deploy/qc45-ui
./install.sh
```

Bei einem bereits vorhandenen Checkout stattdessen den Branch aktualisieren.

Der erste Lauf erfolgt bewusst manuell:

```bash
~/.local/bin/qc45-ui-deploy
```

Erst wenn dieser Lauf erfolgreich war, den Timer einschalten:

```bash
systemctl --user enable --now qc45-ui-deploy.timer
sudo loginctl enable-linger alex
```

Status und Protokoll:

```bash
systemctl --user status qc45-ui-deploy.timer
journalctl --user -u qc45-ui-deploy.service -n 100 --no-pager
```

Automatik abschalten:

```bash
systemctl --user disable --now qc45-ui-deploy.timer
```

## Ablauf nach späteren Änderungen

Nach einem Push in den Branch `native-integration` erkennt der Raspberry die
geänderte UI innerhalb von ungefähr einer Minute. Dabei werden alle
versionierten UI-Klassen gemeinsam kompiliert und vor dem Upload auf
Vollständigkeit und Class-Major-Version 51 geprüft. Änderungen außerhalb von
`ui-patch/src` lösen keinen Austausch auf der QC45 aus.
