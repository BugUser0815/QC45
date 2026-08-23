# Automatisches QC45-UI-Deployment

Der Raspberry prüft im Minutentakt den UI-Quellstand des Branches
`native-integration`. Nur wenn sich `ui-patch/src` geändert hat, wird die
aktuell eingesetzte UI-JAR von der QC45 geladen, der Patch dagegen gebaut und
die neue JAR zurück auf die Säule übertragen.

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

Für Raspberry Pi OS/Debian 12:

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk-headless
```

Der Installer prüft zusätzlich, ob `javac` tatsächlich Java-7-Bytecode für
die alte QC45-Laufzeit erzeugen kann.

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
geänderte UI innerhalb von ungefähr einer Minute. Änderungen außerhalb von
`ui-patch/src` lösen keinen Austausch auf der QC45 aus.
