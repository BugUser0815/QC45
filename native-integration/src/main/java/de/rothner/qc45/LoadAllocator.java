package de.rothner.qc45;

/** Pure AC/DC budget calculation used by {@link LoadManager}. */
final class LoadAllocator {
    private static final double THREE_PHASE_KW_PER_A = 0.692820323d;

    private LoadAllocator() {}

    static Targets plan(boolean dcActive, boolean acActive,
                        int actualDcKw, int actualAcKw,
                        int commandedDcKw, int commandedAcKw,
                        double criticalA, double targetA, double commandCeilingA,
                        double hysteresisA,
                        int minDcKw, int maxDcKw,
                        int minAcKw, int maxAcKw,
                        int rampUpKwPerLoop) {
        if (!dcActive && !acActive) return new Targets(0, 0);

        actualDcKw = dcActive ? clamp(actualDcKw, 0, maxDcKw) : 0;
        actualAcKw = acActive ? clamp(actualAcKw, 0, maxAcKw) : 0;
        commandedDcKw = dcActive ? normalize(commandedDcKw, minDcKw, maxDcKw) : 0;
        commandedAcKw = acActive ? normalize(commandedAcKw, minAcKw, maxAcKw) : 0;

        int currentTotalKw = commandedDcKw + commandedAcKw;
        int actualTotalKw = actualDcKw + actualAcKw;
        int minTotalKw = (dcActive ? minDcKw : 0) + (acActive ? minAcKw : 0);
        int maxTotalKw = (dcActive ? maxDcKw : 0) + (acActive ? maxAcKw : 0);
        double headroomA = targetA - criticalA;

        int desiredTotalKw;
        if (Math.abs(headroomA) < hysteresisA) {
            desiredTotalKw = currentTotalKw;
        } else if (headroomA < 0.0d) {
            desiredTotalKw = Math.min(currentTotalKw,
                (int)Math.floor(currentTotalKw + headroomA * THREE_PHASE_KW_PER_A));
        } else {
            int requestedTotalKw = clamp(
                (int)Math.floor(actualTotalKw + headroomA * THREE_PHASE_KW_PER_A),
                0, maxTotalKw);
            if (requestedTotalKw > currentTotalKw) {
                desiredTotalKw = Math.min(requestedTotalKw,
                    currentTotalKw + Math.max(1, rampUpKwPerLoop));
            } else {
                desiredTotalKw = requestedTotalKw;
            }

            // A connector cannot charge below its configured technical minimum.
            // After a safety pause, release all active minima in one step only if
            // the measured headroom can support that complete starting budget.
            if (currentTotalKw == 0 && requestedTotalKw >= minTotalKw
                    && desiredTotalKw < minTotalKw) {
                desiredTotalKw = minTotalKw;
            }
        }

        desiredTotalKw = clamp(desiredTotalKw, 0, maxTotalKw);
        Targets targets = fairTargets(dcActive, acActive, desiredTotalKw,
            minDcKw, maxDcKw, minAcKw, maxAcKw);

        // Account for power already released but not yet drawn by a vehicle. A
        // target is only safe when a sudden rise up to both connector limits
        // would still remain below the command ceiling. This closes the delayed
        // vehicle-ramp race which a calculation from actual power alone leaves.
        while (targets.totalKw() > 0
                && projectedCurrentA(criticalA, targets, actualDcKw, actualAcKw)
                    > commandCeilingA) {
            desiredTotalKw--;
            targets = fairTargets(dcActive, acActive, desiredTotalKw,
                minDcKw, maxDcKw, minAcKw, maxAcKw);
        }

        if (criticalA >= commandCeilingA) return new Targets(0, 0);
        return targets;
    }

    static Targets fairTargets(boolean dcActive, boolean acActive, int totalBudgetKw,
                               int minDcKw, int maxDcKw,
                               int minAcKw, int maxAcKw) {
        totalBudgetKw = Math.max(0, totalBudgetKw);
        if (!dcActive && !acActive) return new Targets(0, 0);
        if (dcActive && !acActive) {
            return new Targets(normalize(totalBudgetKw, minDcKw, maxDcKw), 0);
        }
        if (!dcActive && acActive) {
            return new Targets(0, normalize(totalBudgetKw, minAcKw, maxAcKw));
        }

        // If equal shares cannot satisfy both technical minima, pause both.
        // Starting just one connector would introduce an implicit priority.
        int equalTechnicalMinKw = Math.max(minDcKw, minAcKw);
        if (totalBudgetKw < 2 * equalTechnicalMinKw) return new Targets(0, 0);

        int equalShareKw = totalBudgetKw / 2;
        int dcKw = normalize(equalShareKw, minDcKw, maxDcKw);
        int acKw = normalize(equalShareKw, minAcKw, maxAcKw);
        int unusedKw = totalBudgetKw - dcKw - acKw;

        // Keep equal limits while both connectors have capacity. An odd kW is
        // deliberately left as reserve instead of favouring either vehicle.
        // Only redistribute once one connector has reached its hardware maximum.
        if (unusedKw > 0 && acKw >= maxAcKw && dcKw < maxDcKw) {
            int extra = Math.min(unusedKw, maxDcKw - dcKw);
            dcKw += extra;
            unusedKw -= extra;
        }
        if (unusedKw > 0 && dcKw >= maxDcKw && acKw < maxAcKw) {
            acKw += Math.min(unusedKw, maxAcKw - acKw);
        }

        return new Targets(dcKw, acKw);
    }

    private static double projectedCurrentA(double measuredCriticalA, Targets targets,
                                            int actualDcKw, int actualAcKw) {
        int unreachedDcKw = Math.max(0, targets.dcKw - actualDcKw);
        int unreachedAcKw = Math.max(0, targets.acKw - actualAcKw);
        return measuredCriticalA
            + (unreachedDcKw + unreachedAcKw) / THREE_PHASE_KW_PER_A;
    }

    private static int normalize(int value, int min, int max) {
        if (value < min) return 0;
        return clamp(value, min, max);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class Targets {
        final int dcKw;
        final int acKw;

        Targets(int dcKw, int acKw) {
            this.dcKw = dcKw;
            this.acKw = acKw;
        }

        int totalKw() { return dcKw + acKw; }
    }
}
