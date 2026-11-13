var active = dc_c1_session != 0 || dc_c2_session != 0;
var nowMs = Date.now();
var budgetKw = 0;
if (dc_mode === "off") {
  dcControlEnabled = false;
  dcStopRequested = true;
  dcTargetKw = 0;
} else if (!active) {
  // Keep an explicit Enable(true), including before OCPP becomes active.
  dcStopRequested = false;
  dcPendingSinceMs = 0;
  budgetKw = dc_mode === "now" ? 5 : 0;
} else if (typeof dcStopRequested !== "undefined" && dcStopRequested) {
  budgetKw = 0;
} else if (typeof dcControlEnabled !== "undefined" && dcControlEnabled) {
  budgetKw = typeof dcTargetKw === "number" ? dcTargetKw : 5;
} else {
  if (typeof dcPendingSinceMs !== "number" || dcPendingSinceMs === 0) {
    dcPendingSinceMs = nowMs;
  }
  budgetKw = dc_mode === "now" && nowMs - dcPendingSinceMs < 10000 ? 5 : 0;
}
var appliedKw = Math.max(0, Math.min(35, budgetKw));
if (typeof dcLastAppliedKw !== "number" || dcLastAppliedKw !== appliedKw) {
  console.info("DC permission: mode=" + dc_mode + " active=" + active + " enabled=" + (typeof dcControlEnabled !== "undefined" && dcControlEnabled) + " target=" + (typeof dcTargetKw === "number" ? dcTargetKw : 0) + " budget=" + appliedKw + "kW");
}
dcLastAppliedKw = appliedKw;
appliedKw;
