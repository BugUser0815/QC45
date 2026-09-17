# SoC-Derating des Akkuboosts

Der Sunny-Island-Cluster schaltet bei sehr niedrigem SoC hart ab. Um diesen Sprung zu vermeiden, begrenzt der `lean`-PeakShaving-Prozess die **maximal angeforderte Entladeleistung** bereits vorher sanft.

## Datenquelle

Sunny Island Modbus/TCP:

| Parameter | Wert |
|---|---:|
| Register | `30845` |
| Typ | U32, FIX0 |
| Standardport | `502` |
| Standard Unit ID | `3` |
| Abfrage | etwa 1× pro Sekunde |

## Kennlinie

- Derating startet beim Fallen bei **20 % SoC**.
- Maximale Entladung dort: **18 kW**.
- Bei **11 % SoC**: **0 kW** Entladung.
- Hysterese: Limiter wird erst ab **21 %** wieder freigegeben.
- Rechenkennlinie: **2 kW je 1 %-Punkt** bzw. theoretisch 1 kW je 0,5 %-Punkt.

```text
max_discharge_W = clamp((SoC - 11) × 2000, 0, 18000)
```

### Effektive Stufen mit Register 30845

| SoC | Max. Entladung |
|---:|---:|
| 20 % | 18 kW |
| 19 % | 16 kW |
| 18 % | 14 kW |
| 17 % | 12 kW |
| 16 % | 10 kW |
| 15 % | 8 kW |
| 14 % | 6 kW |
| 13 % | 4 kW |
| 12 % | 2 kW |
| 11 % | 0 kW |

Da der Sunny Island diesen SoC als ganzzahligen FIX0-Wert liefert, entstehen praktisch 2-kW-Stufen. Die ursprünglich gewünschte 0,5-%-Feinheit ist mit diesem Register allein nicht verfügbar.

## Was wird begrenzt?

Nur die **positive Entladeanforderung** des virtuellen SMA-Meters:

```text
fake_import = min(fake_import, allowedDischarge)
```

Der Fake-Export – also die Ladeanforderung an den Speicher – bleibt unverändert. Dadurch beeinflusst der SoC-Limiter nicht die normale Beladungslogik.

## Fehlerfall

- Noch nie gültigen SoC gelesen → bestehendes Peak Shaving bleibt ohne SoC-Limit aktiv.
- Nach mindestens einem gültigen Read → bei einzelnen Fehlern wird der letzte gültige SoC weiterverwendet.

## Abgrenzung

> [!IMPORTANT]
> Dieses SoC-Derating sitzt **nicht im QC45 LoadManager**. Es schützt den Akkuboost auf der vorgelagerten Peak-Shaving-Ebene.

Quellcode: `https://github.com/BugUser0815/PeakShaving/blob/lean/src/main.cpp`
