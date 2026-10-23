package de.rothner.qc45;

/**
 * Calculates the DC release directly from the remaining KSEM phase-current
 * headroom. No battery/SMA capability is assumed here: any support from the
 * SMA/BYD system is visible only through the measured KSEM currents.
 */
final class KsemDcAllocator {
    // Nominal conversion for reductions; rounding up removes the full excess.
    static final double THREE_PHASE_KW_PER_A = 0.692820323d;
    // Reserve for rectifier losses, voltage variation and delayed KSEM samples.
    static final double DC_KW_PER_GRID_A = 0.60d;
    private static final double RAMP_HEADROOM_FACTOR = 0.75d;
    private static final int CATCHUP_TOLERANCE_KW = 1;

    private KsemDcAllocator() {}

    /** confirmedActualKw is the lower of two consecutive DC power readings. */
    static int targetKw(int commandedKw, int confirmedActualKw,
                        double criticalA,
                        double phaseTargetA, double hysteresisA,
                        int minDcKw,
                        int maxDcKw, int rampUpKwPerLoop) {
        if (commandedKw < 0 || confirmedActualKw < 0
                || !finite(criticalA) || criticalA < 0.0d
                || !finite(phaseTargetA) || phaseTargetA <= 0.0d
                || !finite(hysteresisA) || hysteresisA < 0.0d
                || minDcKw <= 0 || maxDcKw < minDcKw || rampUpKwPerLoop <= 0) {
            throw new IllegalArgumentException("invalid KSEM allocator input");
        }

        // Logical zero still means physical 5-kW Notladen on this hardware.
        int releasedKw = clamp(commandedKw, minDcKw, maxDcKw);
        double headroomA = phaseTargetA - criticalA;
        double holdBandA = Math.min(hysteresisA, phaseTargetA * 0.05d);
        int targetKw = releasedKw;

        if (headroomA < -holdBandA) {
            // Calculate from observed output, not the previous command. A
            // reduction which the station has not yet followed must not be
            // subtracted again on every loop with the same high grid reading.
            targetKw = Math.min(targetKw, (int)Math.floor(
                confirmedActualKw + headroomA * THREE_PHASE_KW_PER_A));
        }

        // Already released but undrawn power can still appear at the grid.
        // Only credit new DC output after a second reading, and never credit
        // an unobserved reduction as headroom. This bound also applies while
        // holding or waiting for the vehicle to catch up.
        int pendingBudgetKw = (int)Math.floor(Math.max(0.0d,
            (headroomA + holdBandA) * DC_KW_PER_GRID_A));
        targetKw = (int)Math.min((long)targetKw,
            (long)confirmedActualKw + pendingBudgetKw);

        if (targetKw < minDcKw) return 0;
        if (targetKw < releasedKw) return targetKw;

        // Both rising and falling output must settle before another increase.
        // Small current fluctuations around the target do not change the limit.
        if (headroomA <= holdBandA
                || confirmedActualKw < releasedKw - CATCHUP_TOLERANCE_KW
                || confirmedActualKw > releasedKw + CATCHUP_TOLERANCE_KW) {
            return releasedKw;
        }

        int headroomStepKw = (int)Math.floor(
            headroomA * DC_KW_PER_GRID_A * RAMP_HEADROOM_FACTOR);
        int rampedKw = limitIncreaseKw(commandedKw,
            (int)Math.min((long)maxDcKw, (long)releasedKw + headroomStepKw),
            minDcKw, rampUpKwPerLoop);
        // A new increase must fit below the target itself, without consuming
        // the hold band reserved for noise on an existing command.
        int safeIncreaseKw = (int)Math.min((long)maxDcKw,
            (long)confirmedActualKw
                + (int)Math.floor(Math.max(0.0d, headroomA) * DC_KW_PER_GRID_A));
        return Math.max(releasedKw, Math.min(rampedKw, safeIncreaseKw));
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /**
     * Gradual DC power INCREASES only. A fresh session starts at physical
     * Notladen (5 kW), regardless of KSEM headroom or an evcc maximum.
     * All decreases remain immediate, including zero and grid failback.
     */
    static int limitIncreaseKw(int previouslyApprovedKw, int safeTargetKw,
                               int minDcKw, int rampUpKwPerLoop) {
        if (previouslyApprovedKw < 0 || safeTargetKw < 0
                || minDcKw <= 0 || rampUpKwPerLoop <= 0) {
            throw new IllegalArgumentException("invalid DC ramp input");
        }
        if (safeTargetKw <= previouslyApprovedKw) return safeTargetKw;
        if (previouslyApprovedKw < minDcKw) return Math.min(safeTargetKw, minDcKw);
        long stepTarget = (long) previouslyApprovedKw + rampUpKwPerLoop;
        return (int)Math.min((long)safeTargetKw, stepTarget);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
