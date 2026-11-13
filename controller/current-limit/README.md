# CCS power-to-current enforcement (EVACom v6 shim)

The affected EVACom stack advertised a power budget while retaining a maximum
current of 125 A and forwarding larger `CurrentDemandReq` targets to the power
electronics. The independent station guard consequently stopped the session.

This preload library implements a current ceiling in the CCS controller. It is
specific to the ARM EABI32 `libETransport.so.1` with SHA-256
`f93941b429cf7c29d1e2e65836de4791de644210ff10164ff640b5e22dfc06fe`.
It requires the verified `CanPowerElectronic` object layout and RTTI. It is not
portable to another controller image without a new binary/layout audit.

## Behavior

- Uses the live explicit CAN power limit during current demand. The positive
  PMax schedule is a fallback only when the explicit field is absent. On this
  firmware the schedule is written during parameter discovery, whereas the
  explicit limit continues to change while charging. Taking their minimum in
  v2/v3 pinned a 5-kW start to its initial schedule and prevented the native
  load manager from completing its ramp.
- Rejects invalid explicit limits and values above the installation's existing
  35-kW native DC hard maximum. In particular, the observed 50-kW shutdown
  default yields zero current permission. An invalid explicit field does not
  fall back to a positive schedule.
- Advertises a dynamic maximum current, bounded by the original hardware limit.
- Before precharge, uses the rated voltage if present voltage is zero. During
  current demand, converts power to current at the measured output voltage.
  The vehicle's target voltage is a CCCV setpoint, not measured battery voltage.
- Computes `floor(power_W / (voltage_V + 1 V))`, bounded by the original
  hardware current limit. At budgets up to 5 kW it subtracts an additional
  1.5 A before rounding, bounded at zero, to cover the recorded low-power
  converter excursion. The added volt gives voltage margin at every budget.
  Above 5 kW there is no additional fixed ampere reserve: whole-amp rounding
  and the voltage margin retain the nominal power bound, while the original
  station guards monitor actual power and phase currents.
  The request voltage remains validated and is forwarded unchanged.
  It clamps a copy of the decoded vehicle request before the CAN serializer.
  Smaller and zero requests remain smaller and zero. The original request,
  controller object, authorization and status are retained.
- Sets consistent current/power limit fields and limit-achieved flags in the
  vehicle response. Invalid limits or voltage yield a zero current ceiling.
  An unexpected runtime ABI/binding stops the application.

At a synthetic 5,000 W / 373.1 V measured output, v6 commands at most 11 A,
including the reserve. This implements a ceiling, not a promise
of exactly 5,000 W output. It does not add freshness information to the original
CAN object; existing communication timeouts and station protections still apply.

## Validation and build

`test_current_limit.c` checks the physical power inequality in 100,000 cases,
preserves zero/fractional requests and original bytes, rejects invalid values
and unexpected RTTI, and covers the original shutdown power-multiplier anomaly.
Half the randomized cases use a live limit with a stale 5-kW schedule. Ten
additional steps exercise increasing and decreasing live limits through
5/6/7/12/25/33/35 kW, including the signed-short power representation above
32,767 W. It also checks the low-power reserve model (+1.5 A and +1 V),
plus the recorded 27-kW catchup case and a synthetic 33-kW case. Another 16,828 high-power model
cases cover every 6--33-kW stage at 370--430 V, including 1 A of converter
underdelivery, against the unchanged native 1-kW catchup threshold. The nominal
physical bound uses measured voltage; it does not assume the output equals the vehicle's setpoint.
It runs both on the host and directly on the ARM controller.

The v6 host sanitizer tests, native ARM tests and real ELF binding probe passed
on 2026-11-13. Deployed SHA-256:
`e650fe75d09bd59f9c0847cc6fc88df1244695d2deacab49a6ae0de0a7e70c68`.
The v6 live measurement passed in one eSprinter session on 2026-11-13:
33-kW permission, controlled reduction to 5 kW, and restored 33-kW permission.
At high permission the settled DC mean was 32.817 kW and sampled peak 33.200 kW.
The strict low-power segment lasted 160.304 s; after a 5-s settling exclusion,
552 samples over 155.243 s peaked at 4.357 kW, with no sample above 5 kW.
The transition had two old-output samples above 5 kW; the first permanently
following below-limit sample was 0.563 s after the incoming CAN budget change.
All 3,422 independently decoded DIN messages re-encoded byte-identically;
1,711 vehicle/CAN target pairs and live response limits were checked.
No guard/permission stop occurred. The original external setting was restored
and the vehicle remained charging. Full evidence and rollback are private under
`autocharge-installation/esprinter-2026-11-13/current-limit-v6/`.

The first timed capture expired before the reconnect, so this is a midstream
measurement, not a fresh-handshake capture. It includes the complete controlled
33-to-5-to-33 sequence. Other vehicles, a cold start after power loss and other
firmware images remain outside this live validation.

Existing grid and load-management targets remain effective. A scheduled site
limit constrained the initial high-power plateau; the measured 33-kW window
started after the existing schedule allowed that budget. Neither the schedule
nor the independent protections were changed.

The evcc reconnect-permission correction is described under `../evcc-control`.
This shim does not replace evcc permission or native OCPP authorization.

```sh
cc -std=c99 -O2 -Wall -Wextra -Werror test_current_limit.c -o test-current-limit
./test-current-limit
```

Build on the target controller with its native GCC:

```sh
gcc -std=c99 -Os -Wall -Wextra -Werror -mcpu=arm926ej-s -marm \
  -mfloat-abi=soft -fPIC -shared -Wl,--no-undefined -Wl,-z,now \
  -Wl,-soname,libqc45_powerlimit_fix.so current_limit.c -ldl \
  -o libqc45_current_limit.so
LD_LIBRARY_PATH=/home/evacom LD_PRELOAD="$PWD/libqc45_current_limit.so" \
  python probe_abi.py "$PWD/libqc45_current_limit.so"
```

`probe_abi.py` loads the actual transport ELF and checks symbol binding,
interposition in its actual vtable, and the new getters on a fake object. It
performs no hardware I/O. Offline tests do not replace a real vehicle test.

## Installation and rollback

The vehicle must be unplugged and the station confirmed idle for 30 seconds
before applying or rolling back. `deploy.py` accepts only the known previous
v1 shim or, with `--from-v2`/`--from-v3`/`--from-v4`/`--from-v5`, the corresponding known build and firmware.
It validates the candidate probe and both persistence
wrappers, backs up all affected files and modes in a new private directory,
then stops the existing application screen before changing files.

```sh
python deploy.py --vehicle-unplugged --candidate /absolute/stage/libqc45_current_limit.so \
  --sha256 VERIFIED_CANDIDATE_SHA256 --backup /home/NEW_PRIVATE_BACKUP
# Add --from-v2, --from-v3, --from-v4 or --from-v5 for the known installed version.
```

The combined shim replaces the prior power fallback at
`/home/qc45-fixes/power-limit/libqc45_powerlimit_fix.so`. Both EVAStart wrappers
and `/home/qc45_power_persist.py` are updated to the new checksum and persistent
source copy. A checksum mismatch holds the application stopped. The installer
requires one stable application process with the library loaded and validates
persistence; a failed start restores the previous files and application.

Only the CCS application is restarted. Existing Autocharge observation and
station guards remain running. The transport ELF is unchanged.

```sh
python deploy.py --vehicle-unplugged --rollback --backup /home/EXISTING_PRIVATE_BACKUP
```

Rollback restores the immediately previous installation, including its shim,
helper and both start scripts. Do not substitute the old helper's `--remove`,
which restores factory scripts instead. Live traces, vehicle identifiers,
deployment checksums and test results belong in the ignored installation folder.
