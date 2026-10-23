# CCS-Controller EVAcharge SE: Zugang und Panda-Fehlersuche

**Stand: 24.10.2026 (Europe/Berlin), Untersuchung vom 23. auf den 24.10.2026.**

## Ergebnis und Belegstand

Der separate HLC-Controller der untersuchten QC45 ist durch erfolgreichen SSH-Zugang als **EVAcharge SE** bestätigt. Laut Betreiber ist das Board per Ethernet am internen Switch angeschlossen. Die Anwendungsausgabe liefert nun Informationen über den fahrzeugseitigen CCS-Dialog, die im aggregierten Geode-Status fehlen.

Die Befunde beruhen auf den vom Betreiber übermittelten Terminalausgaben. Der analysierende Assistent hatte selbst keinen direkten Netzwerkzugriff zur Station. Die frühere reine EVAcharge-Vermutung ist damit ersetzt; die physische Zuordnung **A19 / Efacec 20090007** und die Boardrevision bleiben ohne Prüfung des Aufdrucks offen.

Diese Fassung enthält die fachlichen Ergebnisse und Diagnoseverfahren. Interne Netzwerkadressen, Zugangsdaten, gerätespezifische Konfiguration und vollständige Diagnose-Rohdaten sind nicht veröffentlicht.

## Zugang wiederfinden

Der erfolgreiche Zugriff erfolgte **vom Geode aus**. Obwohl Controller und Geode am gleichen Switch hängen, war für den Test eine zusätzliche temporäre Adresse im Controller-Subnetz erforderlich. Die vorherige ARP-Tabelle zeigte nur den Router und schloss den angeschlossenen Controller somit nicht aus.

Vorgehen für einen erneuten Zugriff:

1. Am Geode die aktuelle Netzwerkkonfiguration mit `ifconfig -a`, `route -n` und `arp -an` lesen.
2. Die aktuell gültige Controlleradresse und eine freie Diagnoseadresse im selben Subnetz bestimmen. Nicht davon ausgehen, dass alte Adressen unverändert gelten.
3. Eine zusätzliche temporäre Adresse auf dem passenden Geode-Interface ergänzen, ohne dessen Hauptadresse und Standardroute zu ersetzen.
4. Erreichbarkeit und ARP-Auflösung prüfen und die Controller-SSH-Sitzung öffnen.
5. Nach der Diagnose die Sitzung verlassen und die zusätzliche Adresse wieder entfernen, sofern sie nicht für weitere Diagnose benötigt wird.

Allgemeine Plattform-Werkseinstellungen beschreibt die öffentliche Herstellerdokumentation: [BSP Login](https://evacharge-se-bsp.readthedocs.io/en/latest/login.html) und [BSP Networking](https://evacharge-se-bsp.readthedocs.io/en/latest/networking.html). Diese Angaben ersetzen keine Prüfung der konkreten Stationskonfiguration.

## Was die Controller-Untersuchung ergeben hat

- Die Controlleranwendung läuft in einer bestehenden **GNU-Screen-Sitzung**.
- Prozess, Arbeitsverzeichnis, Startskript und Versionsdatei wurden ermittelt.
- Das Startskript startet die Anwendung nach deren Ende erneut. Zur Diagnose soll die vorhandene Sitzung genutzt werden.
- Eine aktive PLC-Netzwerkschnittstelle und eine aktive SocketCAN-Schnittstelle wurden beobachtet. Die genaue CAN-Nachrichtenbelegung und Verbindung zur übrigen Ladehardware sind noch nicht bewiesen.
- Die gelisteten Bibliotheken deuten auf getrennte SLAC-, Transport-, Lade-, CAN- und serielle Komponenten hin. Ihre Inhalte wurden noch nicht statisch ausgewertet.
- Die Versionsdatei enthält eine datumsförmige Softwarekennung. Kernel- oder Dateidaten allein beweisen weder den vollständigen Anwendungsstand noch die Ursache des Fehlers.
- In der vorgelegten Konfiguration wurden keine offensichtlichen Stations-Maximalwerte für Spannung, Strom und Leistung und kein eindeutiger Logging-Schalter gefunden.
- Einige INI-Parameter sind ausdrücklich für die Fahrzeugrolle bzw. Tests beschrieben. Daraus dürfen keine ungeprüften Stationsgrenzen oder TLS-Einstellungen abgeleitet werden.
- `command -v tcpdump` lieferte im Controller-PATH keinen Treffer. Andere Installationsorte wurden damit nicht ausgeschlossen. Ein vollständiger PLC-Paketmitschnitt liegt noch nicht vor.

## Controllerausgabe ohne Neustart sichern

Mit `ps axww` und `screen -ls` zuerst die **aktuelle** Screen-Sitzungskennung bestimmen. Prozessnummern sind nicht dauerhaft.

Der folgende Ablauf ist eine Vorlage; den Platzhalter durch die geprüfte Kennung der bestehenden Anwendungssitzung ersetzen:

```bash
screen -S <SITZUNG> -p 0 -X hardcopy -h /tmp/ccs-screen.txt
tail -n 200 /tmp/ccs-screen.txt
```

Das Verfahren wurde erfolgreich angewendet. `hardcopy -h` sichert Bildschirm und vorhandenen Rücklaufpuffer, ohne die Anwendung neu zu starten. Es ist kein vollständiger historischer Mitschnitt und ergänzt keine fehlenden Zeitstempel. Befehle einzeln mit Zeilenumbruch eingeben; zusammengeklebte Befehle waren die Ursache des ersten fehlgeschlagenen Versuchs.

Referenz: [GNU Screen: Hardcopy](https://www.gnu.org/software/screen/manual/html_node/Hardcopy.html).

## Konkrete Abbruchstelle

Der übermittelte Rücklauf enthält zwei sichtbare Sitzungsenden mit demselben Übergang:

| Phase | Beobachtung |
|---|---|
| SLAC | In der zweiten Sitzung Match-Anfrage und als gesendet protokollierte Bestätigung sichtbar; die erste Sitzung beginnt im Ausschnitt erst später |
| SDP | Anfrage empfangen und geprüft |
| SupportedAppProtocol / SessionSetup | Verarbeitung sichtbar, Sitzung als neu aufgebaut gemeldet |
| Dienste und Auswahl | Lokale Antworten als erfolgreich protokolliert |
| ContractAuthentication | Mehrere Anfragen, danach Übergang zur Parameterabfrage |
| ChargeParameterDiscovery | Anfrage verarbeitet, lokal erfolgreicher Antwortcode, anschließend wird CableCheck oder SessionStop erwartet |
| CableCheck / PreCharge | Keine entsprechend empfangenen Anfragen im vorgelegten Ausschnitt |
| Ende | TCP-Verlust, danach PWM-Rücksetzung, Disconnect und virtuelles Entriegeln |

**Belastbar ist die sichtbare Abbruchstelle nach Verarbeitung von ChargeParameterDiscovery und vor einer sichtbaren CableCheck-Anfrage.** Das passt zu den zuvor beobachteten 0 V / 0 A.

Ein lokaler erfolgreicher Antwortcode beweist weder korrekte Übertragung und EXI-Kodierung noch Akzeptanz durch das Fahrzeug. Aus der Meldung zum TCP-Verlust ist nicht erkennbar, wer die Verbindung beendet hat. Ein Zeitstempelbezug zum konkreten Panda-Versuch und zum DTC-4008-Ereignis fehlt noch.

## Was weiterhin offen ist

- Inhalt und Kodierung der **ChargeParameterDiscoveryRes**, insbesondere Grenzwerte, Einheiten/Multiplikatoren, EVSE-Status und Verarbeitungsstatus.
- Ursache und Initiator des TCP-Abbaus: Controller, Fahrzeug, Timeout oder PLC-Verlust sind noch nicht unterschieden.
- Tatsächlich ausgehandelter DIN-/ISO-Standard. Das interne QuickCharge V3 ist hiervon unabhängig.
- Tatsächlich verwendete Transportverschlüsselung. Weder eine TLS-Konfigurationsmeldung noch eine Fahrzeug-Testoption beweist den Transportmodus.
- Die wiederholte Authentifizierung ist nicht als Fehler nachgewiesen; die Verarbeitung erreicht anschließend die Parameterabfrage.
- Exakte Bedeutung und Entstehung des aggregierten Fehlercodes 4008 innerhalb der Ladehardware.
- Bezug des CAN-Verkehrs zu den vom Controller erzeugten Stationsparametern.

**Arbeitshypothese:** Als Nächstes die Parameterantwort und zugehörige Transport-/Zustandsbehandlung prüfen. Unpassende Werte, Kodierung und Timeout sind Kandidaten, keine festgestellten Ursachen. Es gibt noch keine belastbare Schuldzuweisung an Fahrzeug oder Station.

## Grenzen des Geode-Pfads

Die statische Untersuchung der vollständigen Geode-Software zeigt den gemeinsamen Master-/QuickCharge-Pfad. Der V3-Status liefert zusammengefasste Werte einschließlich Fehlercode, aber nicht automatisch den vollständigen fahrzeugseitigen DIN-/ISO-Dialog.

Die Geode-Webfunktion für Debug/Logexport sammelt Geode-Logs. Ein vorhandenes Master-Updatewerkzeug ist kein belegter Shell- oder Diagnosezugang zum Linux des EVAcharge. Ein USB-Seriell-Portname allein darf nicht zur Identifikation des CCS-Zugangs verwendet werden; entscheidend ist die tatsächliche Objekt- und Gerätezuordnung.

Das EVAcharge besitzt eigenen Speicher. Ein Geode-SD-Image enthält dessen Programme nicht automatisch. Mit dem direkten Controllerzugang ist daher eine zusätzliche Diagnoseebene erschlossen.

## Nächste Schritte

1. Die angeforderten Controllerprogramme und Bibliotheken für statische Analyse bereitstellen. Das Programmarchiv lag bei dieser Dokumentation noch nicht vor; seine Erstellung und Auswertung sind nicht als abgeschlossen zu behandeln.
2. Erzeugung der Parameterantwort, CAN-Parameterzuordnung, Versionsinformationen und vorhandene Diagnosefunktionen untersuchen.
3. Einen neuen, eindeutig dem Panda zugeordneten Versuch zeitlich mit Geode-Log und Controllerausgabe korrelieren. Zuerst die Controllerzeit prüfen; undatierte Rücklaufzeilen und unplausible Dateizeiten reichen nicht.
4. Einen passenden passiven PLC-Mitschneider unter Berücksichtigung von Prozessorarchitektur, ABI und altem Userspace vorbereiten. Ein PCAP allein ist bei TLS nicht automatisch im Klartext lesbar.
5. Vor einem Softwareupdate vorhandenen Stand und Boardrevision sichern. Ein generisches Linux-BSP enthält nicht automatisch die Efacec-Ladeanwendung und ist kein nachgewiesen kompatibles Stationsupdate.

In dieser Untersuchung wurden keine Konfigurationswerte geändert und kein Controllerupdate durchgeführt.

## Öffentliche Quellen

- [chargebyte: EVAcharge SE, Datenblatt und Errata](https://chargebyte.com/products/controllers-modules/evse-controllers/evacharge-se)
- [BSP: Inhalt und Abgrenzung zum ISO-Stack](https://evacharge-se-bsp.readthedocs.io/en/latest/general.html)
- [chargebyte: BSP-Bauquellen](https://github.com/chargebyte/chargebyte-bsp)
- [Akkodis: EVAcharge / Auronik-Herkunft](https://www.akkodis.com/de/technologische-verfahren/wireless-konnektivitat/e-mobility-ladeinfrastruktur)
- [DEWA-Ersatzteilanfrage: A19 als CCS Board mit Teilenummer 20090007](https://www.dewa.gov.ae/api/RfxDownload/Get/2012403320)

Die öffentlichen Errata zu bestimmten frühen Serien sind kein Beleg für eine Betroffenheit der untersuchten Station. Funktionen heutiger Produkte oder BSP-Versionen sind nicht automatisch auf ihre alte Controlleranwendung übertragbar.

Siehe auch: [Systemarchitektur](Systemarchitektur), [Reverse Engineering](Reverse-Engineering), [CCS Raw Tracing](CCS-Raw-Tracing), [CCS QuickCharge V3](CCS-QuickCharge-V3).
