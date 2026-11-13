# evcc custom DC control, shared VM v3

These are the JavaScript blocks used by the installed custom QC45 DC charger,
not the separate Go driver in the evcc fork. The installation's complete YAML
and before/after snapshots are private under the ignored installation folder.

The previous refresh block cleared `dcControlEnabled` while no OCPP session was
visible. This could erase a preceding `Enable(true)`. Its startup grace then
expired and wrote 0 kW, causing the station's permit guard to stop a valid
session. The old `enabled` getter also treated the 5-kW prearm budget as an
accepted evcc enable.

- `enabled.js`: reports the shared evcc enable and a positive station budget.
- `enable.js`: stores the explicit enable/stop and resets startup tracking.
- `maxcurrent.js`: stores the external target, converted to a 0/5..35-kW budget.
- `refresh.js`: retains an explicit enable across idle/start transitions and
  refreshes the budget. Mode off, an active stop and the bounded missing-enable
  grace still withdraw permission. Changes of budget are logged.

Use `vm: qc45_dc_control_v3` for all four JS providers. Keep the existing
watchdog refresh interval, mode/session inputs and the single register-110
output under `refresh.js`. Metering, identification, OCPP authorization and
station protections are unchanged by these blocks.

After saving, restart evcc with all loadpoints idle. Its watchdog uses a
background context; replacing/testing a configured provider can leave the old
watchdog alive until process exit. Verify a new process, the v3 log marker and
the saved YAML before a real reconnect test. Do not bypass station permit or
power protections to compensate for a failed control path.

Run the pure state-transition tests with Node:

```sh
node controller/evcc-control/test-control.mjs
```

The tests reproduce the old lost-enable path, execute 96 combinations of mode,
connector, current and connection order, and verify stop, mode off, missing
enable expiry and reconnect behavior. They exercise the exact JS text from the
deployed YAML; they perform no hardware I/O. Live verification is recorded
separately from these offline checks.
