# Build & Installation

## QC45 Native Integration

Voraussetzungen: Maven und ein JDK, das den Java-7-Zielstand bauen kann.

```bash
cd native-integration
mvn clean package
```

Ergebnis:

```text
target/qc45-integration-0.1.0.jar
```

### Installation auf der QC45

```bash
cp target/qc45-integration-0.1.0.jar \
  /home/mobie/evcsd/webapps/ROOT/WEB-INF/lib/qc45-integration.jar
```

Konfiguration:

```bash
cp qc45-integration.properties.example \
  /home/mobie/evcsd/qc45-integration.properties
```

In `/home/mobie/evcsd/webapps/ROOT/WEB-INF/web.xml` innerhalb von `<web-app>`:

```xml
<listener>
  <listener-class>de.rothner.qc45.BootstrapListener</listener-class>
</listener>
```

Danach EVCSD/Tomcat neu starten.

Erwartete Meldungen sind unter anderem:

```text
[QC45] native integration started
[QC45] Modbus TCP listening on 1502 (multi-client)
[QC45] OCPP15 bridge listening on http://127.0.0.1:9000/QC45
```

## QC45 Lademonitor-UI

Die proprietäre EVCSD-UI-JAR wird nicht im Repository abgelegt. Das Buildskript
verwendet die aktuell eingesetzte JAR als Basis und ersetzt ausschließlich den
reproduzierbar versionierten Lademonitor:

Auf dem Raspberry werden OpenJDK 21 für `jar`/`javap` und `ecj` für den
Java-7-Bytecode verwendet:

```bash
sudo apt install -y openjdk-21-jdk-headless ecj
```

```bash
cd ui-patch
chmod +x build.sh
./build.sh /pfad/evcsdUI-global-charge-cache-refresh.jar
```

Ergebnis:

```text
ui-patch/target/evcsdUI-qc45-clean-charge-screen.jar
```

Vor dem Austausch die aktive Datei sichern. Danach die neue JAR unter dem
Namen der bisherigen UI-JAR nach `/home/mobie/evcsd/ui/lib/` kopieren und den
UI-Prozess neu starten. Details und Datenquellen stehen unter
[Lademonitor UI](Lademonitor-UI).

### Automatisches UI-Deployment über den Raspberry

Unter `deploy/qc45-ui` liegt ein Pull-Deployer für den Raspberry. Er prüft den
Branch `native-integration` im Minutentakt und reagiert ausschließlich auf
Änderungen unter `ui-patch/src`.

Der Ablauf ist bewusst auf die vorhandene Säule zugeschnitten:

1. aktive Basis-JAR von
   `/home/mobie/evcsd/ui/lib/evcsdUI-v4_EFACEC-ALL_IN_ONE_GENERIC.jar` laden,
2. neue UI-Klasse als Java-7-Bytecode bauen und prüfen,
3. Übertragung per SHA-256 verifizieren,
4. aktive JAR sichern und atomar ersetzen,
5. ausschließlich `pt.efacec.es.evcsd.ui.Main 5678` beenden,
6. Neustart durch den vorhandenen UI-Watchdog überwachen,
7. bei instabilem Neustart automatisch die vorherige JAR wiederherstellen.

EVCSD/Tomcat, Modbus und die Ladesteuerung werden dabei nicht neu gestartet.
Installation und SSH-Einrichtung sind in
[`deploy/qc45-ui/README.md`](../deploy/qc45-ui/README.md) beschrieben.

## PeakShaving `lean`

```bash
git switch lean
git pull --ff-only
git submodule update --init --recursive
cmake -S . -B build
cmake --build build -j
```

Start ohne SoC-Limiter:

```bash
./build/peakshaving
```

Mit Sunny-Island-SoC:

```bash
./build/peakshaving <ksem-ip> <peak-watt> <ksem-port> <ksem-unit> <si-ip> [si-port] [si-unit]
```

## EVCC-Fork

Für den selbst gebauten EVCC-Fork wurden unter anderem folgende Buildschritte verwendet:

```bash
make clean
make install
make install-ui
make
```

Für spätere Builds kann je nach Repo-Stand `make build` genügen. Der relevante Treiber liegt im Branch `QC45`.

## Vor Änderungen sichern

Vor dem Austausch der JARs mindestens sichern:

- bestehendes `WEB-INF/lib`
- `web.xml`
- `qc45-integration.properties`
- aktive Original-/Patch-UI-JAR
- relevante EVCSD-Datenbank-/Konfigurationsdateien

## Wiki-Sync

Der Branch enthält `.github/workflows/publish-wiki.yml`. Änderungen unter `wiki/**` werden dadurch in das separat von GitHub geführte QC45-Wiki synchronisiert.

Siehe auch: [Konfiguration & Betrieb](Konfiguration-und-Betrieb).
