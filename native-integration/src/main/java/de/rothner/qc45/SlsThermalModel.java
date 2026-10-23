package de.rothner.qc45;

/**
 * Supplemental, deliberately fast thermal exposure monitor for an E-type SLS.
 * Heat follows I^2 separately on every phase, including gradual cooling.
 * This is a software protection policy, not a manufacturer's trip-time replica.
 */
final class SlsThermalModel {
    static final double E_HOLD_MULTIPLE = 1.05d;
    static final double E_MAGNETIC_MULTIPLE = 5.0d;
    static final long MAX_TIME_CONSTANT_MS = 30000L;
    private static final double TRIP_HEAT = E_HOLD_MULTIPLE * E_HOLD_MULTIPLE;
    private final double nominalA;
    private final long timeConstantMs;
    // Assume a breaker already warmed by continuous rated load at startup.
    private final double[] heat = { 1.0d, 1.0d, 1.0d };
    private final double[] previous = new double[3];
    private long lastAt = -1L;

    SlsThermalModel(double nominalA, long timeConstantMs) {
        if (!finite(nominalA) || nominalA < 16.0d || nominalA > 100.0d
                || timeConstantMs < 1000L || timeConstantMs > MAX_TIME_CONSTANT_MS) {
            throw new IllegalArgumentException("SLS E nominal current must be 16..100A"
                + " and thermalTimeConstantMs must be 1000..30000");
        }
        this.nominalA = nominalA;
        this.timeConstantMs = timeConstantMs;
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
            double retained = Math.exp(-(double)(now - lastAt) / timeConstantMs);
            for (int i = 0; i < heat.length; i++) {
                // Do not credit a falling sample for the entire preceding gap.
                double multiple = Math.max(previous[i], next[i]) / nominalA;
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
