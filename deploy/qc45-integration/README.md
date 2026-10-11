# Automatisches QC45-Integration-Deployment

Der Deployer prüft den Branch `main`, baut und testet die native
Integration und installiert die JAR atomar in der tatsächlich von der QC45
verwendeten Smartgrid-Webanwendung:

```text
/home/mobie/evcsd/webapps/smartgrid/WEB-INF/lib/qc45-integration.jar
```

Nach jedem echten oder erzwungenen Deployment wird die **komplette QC45 neu
gestartet**. Ein reiner Prüflauf ohne Quelländerung löst keinen Reboot aus.

## Ablauf und Absicherung

- Maven-Build einschließlich aller Tests
- Prüfung auf Java-7-Bytecode und zentrale Integrationsklassen
- SHA-256-Prüfung nach der Übertragung
- Prüfung des `BootstrapListener` in `smartgrid/WEB-INF/web.xml`
- gemeinsamer Lock mit dem vorhandenen UI-Deployer; beide Deployments können
  die Station nicht gleichzeitig verändern oder neu starten
- Sicherung der vorhandenen JAR und atomarer Austausch
- vollständiger Systemneustart der QC45
- Prüfung einer geänderten Kernel-Boot-ID
- Warten auf SSH, EVCSD und den Start der nativen Integration
- automatisches JAR-Rollback mit erneutem Komplettneustart, wenn der neue Stand
  nicht sauber startet
- Aufbewahrung der fünf neuesten Sicherungen

## Voraussetzungen

Auf dem Deploy-Rechner werden Git, Maven und OpenJDK 21 benötigt. Diese Pakete
sind direkt in Debian 13 (Trixie) verfügbar, auch auf dem Raspberry Pi:

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk-headless maven
```

Der Deployer sucht OpenJDK 21 unter `/usr/lib/jvm/java-21-openjdk-*`. Maven
verwendet den fest konfigurierten Eclipse-Compiler statt `javac`, damit trotz
Java 21 weiterhin echten Java-7-Bytecode für die alte QC45-Laufzeit erzeugt
werden kann. Bei einer abweichenden Installation kann `QC45_JAVA_HOME` in der
lokalen Konfiguration gesetzt werden.

Der vorhandene QC45-Deployschlüssel wird weiterverwendet. Die Konfiguration
enthält standardmäßig:

```text
root@10.0.40.50
~/.ssh/qc45_deploy
```

## Installation oder Aktualisierung

```bash
cd ~/Development/QC45
git fetch origin
git checkout main
git pull --ff-only origin main
cd deploy/qc45-integration
./install.sh
```

Bestehende Konfigurationen bleiben erhalten. Nur dokumentierte alte
QC45-Adressen (10.0.20.108, 10.0.0.50, 10.0.0.156) werden im Feld
`QC45_SSH_TARGET` auf `root@10.0.40.50` migriert; davor wird eine
Sicherung der bisherigen Konfiguration erstellt.

## Erster Test

```bash
~/.local/bin/qc45-integration-force-deploy
```

Der Befehl endet erst erfolgreich, wenn die Station tatsächlich herunter- und
wieder hochgefahren ist und die Integration zusammen mit EVCSD läuft.

## Automatik

Nach dem erfolgreichen Test:

```bash
systemctl --user enable --now qc45-integration-deploy.timer
sudo loginctl enable-linger alex
```

Status und Protokoll:

```bash
systemctl --user status qc45-integration-deploy.timer
journalctl --user -u qc45-integration-deploy.service -n 200 --no-pager
```

Der Timer prüft minütlich. Nur Änderungen an `native-integration/src` oder
`native-integration/pom.xml` lösen Build, Installation und Reboot aus.

Die Deployer laufen lokal auf dem Raspberry: GitHub-Änderungen werden erst
nach erneutem Ausführen von `./install.sh` wirksam. Für `10.0.40.50` muss
der SSH-Hostschlüssel vor dem ersten automatischen Lauf geprüft und im
lokalen `known_hosts` hinterlegt werden (`StrictHostKeyChecking=yes`).
