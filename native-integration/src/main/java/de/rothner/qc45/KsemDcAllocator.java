package de.rothner.qc45;

/**
 * Calculates the DC release directly from the remaining KSEM phase-current
 * headroom. No battery/SMA capability is assumed here: any support from the
 * SMA/BYD system is visible only through the measured KSEM currents.
 */
final class KsemDcAllocator {
    // sqrt(3) * 400 V / 1000 = balanced three-phase kW per ampere.
    static final double THREE_PHASE_KW_PER_A = 0.692820323d;

    private KsemDcAllocator() {}

    static int targetKw(int releasedKw,
                        KsemClient.Currents currents,
                        double phaseLimitA,
                        int minDcKw,
                        int maxDcKw) {
        if (currents == null) throw new IllegalArgumentException("currents are required");
        return targetKw(releasedKw, currents.max(), phaseLimitA, minDcKw, maxDcKw);
    }

    static int targetKw(int releasedKw,
                        double criticalA,
                        double phaseLimitA,
                        int minDcKw,
                        int maxDcKw) {
        if (phaseLimitA <= 0.0d || minDcKw <= 0 || maxDcKw < minDcKw
                || Double.isNaN(criticalA) || Double.isInfinite(criticalA)) {
            throw new IllegalArgumentException("invalid KSEM allocator input");
        }

        int released = clamp(releasedKw, 0, maxDcKw);
        double headroomA = phaseLimitA - criticalA;
        int deltaKw = (int)Math.floor(headroomA * THREE_PHASE_KW_PER_A);
        int target = clamp(released + deltaKw, 0, maxDcKw);

        // QC45 DC cannot be commanded below its technical minimum. A target
        // below that threshold therefore becomes a logical pause; the existing
        // coordinator keeps the charger's native 0-kW ambiguity fail-safe.
        if (target < minDcKw) return 0;
        return target;
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
