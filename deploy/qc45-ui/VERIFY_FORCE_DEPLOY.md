# Force-Deploy prüfen

Nach `./install.sh` steht der manuelle Force-Deploy unter
`~/.local/bin/qc45-ui-force-deploy` zur Verfügung.

Der Befehl ignoriert absichtlich einen bereits passenden Wert in
`~/.local/state/qc45-ui-deployer/deployed-ui-tree` und baut die Oberfläche
vollständig neu aus dem aktuellen Branch `native-integration`.

## Ausführen

```bash
~/.local/bin/qc45-ui-force-deploy
```

Erwartete Meldung am Anfang:

```text
Force deployment requested; rebuilding UI even if tree ... is already deployed.
```

Am Ende muss eine Meldung der Form erscheinen:

```text
QC45 UI deployment completed from commit <sha>.
```

## Danach auf der QC45 prüfen

```bash
ssh \
  -i ~/.ssh/qc45_deploy \
  -o HostKeyAlgorithms=+ssh-rsa \
  -o PubkeyAcceptedAlgorithms=+ssh-rsa \
  root@10.0.0.156 \
  "jar tf /home/mobie/evcsd/ui/lib/evcsdUI-v4_EFACEC-ALL_IN_ONE_GENERIC.jar | grep -E 'WaitingForCard.class|WaitingForCardChargingTimer.class|sgs-logo.png'"
```

Erwartet werden mindestens:

```text
pt/efacec/es/evcsd/ui/WaitingForCard.class
pt/efacec/es/evcsd/ui/WaitingForCardChargingTimer.class
pt/efacec/es/evcsd/ui/sgs-logo.png
```
