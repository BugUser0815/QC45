package de.rothner.qc45;

/** Detects stable unused connector entitlement without trapping a later ramp. */
final class DemandTracker {
    private final long stableMs;
    private final int reserveKw;

    private boolean demandLimited;
    private long unusedSince;

    DemandTracker(long stableMs, int reserveKw) {
        this.stableMs = Math.max(0L, stableMs);
        this.reserveKw = Math.max(1, reserveKw);
    }

    void update(long now, boolean active, int actualKw, int commandedKw,
                int fairEntitlementKw, int technicalMinimumKw) {
        if (!active || fairEntitlementKw < technicalMinimumKw) {
            reset();
            return;
        }

        actualKw = Math.max(0, actualKw);
        commandedKw = Math.max(0, commandedKw);

        if (demandLimited) {
            // The connector always retains reserveKw above its last observed
            // demand. As soon as the vehicle consumes that probe reserve, return
            // to equal sharing immediately so a rising demand cannot be trapped.
            int wakeThresholdKw = Math.max(technicalMinimumKw,
                commandedKw - Math.max(1, reserveKw - 1));
            if (actualKw >= wakeThresholdKw
                    || actualKw + reserveKw >= fairEntitlementKw) {
                reset();
            }
            return;
        }

        if (actualKw + reserveKw < fairEntitlementKw) {
            if (unusedSince == 0L) unusedSince = now;
            if (now - unusedSince >= stableMs) demandLimited = true;
        } else {
            unusedSince = 0L;
        }
    }

    boolean isDemandLimited() { return demandLimited; }

    void reset() {
        demandLimited = false;
        unusedSince = 0L;
    }
}
