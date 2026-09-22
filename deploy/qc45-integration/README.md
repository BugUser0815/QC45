# Automatisches QC45-Integration-Deployment

Der Deployer prüft den Branch `native-integration`, baut und testet die native
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
- Sicherung der vorhandenen JAR und atomarer Austausch
- vollständiger Systemneustart der QC45
- Prüfung einer geänderten Kernel-Boot-ID
- Warten auf SSH, EVCSD und den Start der nativen Integration
- automatisches JAR-Rollback mit erneutem Komplettneustart, wenn der neue Stand
  nicht sauber startet
- Aufbewahrung der fünf neuesten Sicherungen

## Voraussetzungen

Auf dem Deploy-Rechner werden Git, Maven und OpenJDK 17 benötigt:

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk-headless maven
```

Der vorhandene QC45-Deployschlüssel wird weiterverwendet. Die Konfiguration
enthält standardmäßig:

```text
root@10.0.20.108
~/.ssh/qc45_deploy
```

## Installation oder Aktualisierung

```bash
cd ~/Development/QC45
git fetch origin
git checkout native-integration
git pull --ff-only origin native-integration
cd deploy/qc45-integration
./install.sh
```

Der Installer überschreibt eine bereits vorhandene lokale Konfiguration nicht.

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
