package de.rothner.qc45;

/**
 * Supplemental overload exposure monitor for an E-type SLS.
 * Heat follows I^2 separately on every phase, including gradual cooling.
 * This is a software protection policy, not a manufacturer's trip-time replica.
 */
final class SlsThermalModel {
    static final double E_HOLD_MULTIPLE = 1.05d;
    static final double E_FAST_MULTIPLE = 1.20d;
    static final double E_MAGNETIC_MULTIPLE = 5.0d;
    static final long MAX_TIME_CONSTANT_MS = 30000L;
    static final long DEFAULT_MILD_TIME_CONSTANT_MS = 600000L;
    private static final double TRIP_HEAT = E_HOLD_MULTIPLE * E_HOLD_MULTIPLE;
    private final double nominalA;
    private final long timeConstantMs;
    private final long mildTimeConstantMs;
    // Assume a breaker already warmed by continuous rated load at startup.
    private final double[] heat = { 1.0d, 1.0d, 1.0d };
    private final double[] previous = new double[3];
    private long lastAt = -1L;

    SlsThermalModel(double nominalA, long timeConstantMs) {
        this(nominalA, timeConstantMs, DEFAULT_MILD_TIME_CONSTANT_MS);
    }

    SlsThermalModel(double nominalA, long timeConstantMs, long mildTimeConstantMs) {
        if (!finite(nominalA) || nominalA < 16.0d || nominalA > 100.0d
                || timeConstantMs < 1000L || timeConstantMs > MAX_TIME_CONSTANT_MS) {
            throw new IllegalArgumentException("SLS E nominal current must be 16..100A"
                + " and thermalTimeConstantMs must be 1000..30000");
        }
        if (mildTimeConstantMs < 600000L || mildTimeConstantMs > 3600000L) {
            throw new IllegalArgumentException("mildThermalTimeConstantMs must be 600000..3600000");
        }
        this.nominalA = nominalA;
        this.timeConstantMs = timeConstantMs;
        this.mildTimeConstantMs = mildTimeConstantMs;
    }

    void update(long now, KsemClient.Currents currents) {
        if (currents == null) throw new IllegalArgumentException("grid currents required");
        double[] next = { currents.l1, currents.l2, currents.l3 };
        for (int i = 0; i < next.length; i++) {
            if (!finite(next[i]) || next[i] < 0.0d) {
                throw new IllegalArgumentException("invalid grid phase current");
            }
        }
        if (lastAt >= 0L && now > lastAt) {
            for (int i = 0; i < heat.length; i++) {
                // Do not credit a falling sample for the entire preceding gap.
                double multiple = Math.max(previous[i], next[i]) / nominalA;
                // Keep the existing fast response at >= 1.20 In. Near rated
                // current give the power controller time to settle, rather
                // than turning a small one-minute excess into a session stop.
                // The same slow time base applies to cooling: brief dips do
                // not erase previous overload or artificially restore margin.
                double severity = Math.max(0.0d, Math.min(1.0d,
                    (multiple - E_HOLD_MULTIPLE) / (E_FAST_MULTIPLE - E_HOLD_MULTIPLE)));
                double tau = mildTimeConstantMs
                    + severity * (timeConstantMs - mildTimeConstantMs);
                double retained = Math.exp(-(double)(now - lastAt) / tau);
                heat[i] = heat[i] * retained + multiple * multiple * (1.0d - retained);
            }
        }
        // A clock correction must not suspend protection until wall time catches up.
        lastAt = now;
        System.arraycopy(next, 0, previous, 0, next.length);
    }

    /** Missing measurements never count as a cooling period. */
    void forgetReading() { lastAt = -1L; }

    boolean overloaded() {
        // At exactly 1.05 In the state approaches the boundary without crossing.
        return maximumHeat() > TRIP_HEAT + 0.000000001d;
    }

    double exposure() {
        return Math.max(0.0d, (maximumHeat() - 1.0d) / (TRIP_HEAT - 1.0d));
    }

    private double maximumHeat() {
        return Math.max(heat[0], Math.max(heat[1], heat[2]));
    }

    static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
