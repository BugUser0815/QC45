package de.rothner.qc45;

/** Pure AC/DC budget calculation used by {@link LoadManager}. */
final class LoadAllocator {
    private static final double THREE_PHASE_KW_PER_A = 0.692820323d;
    // Conservative command projection. Type2 may charge on only one 230 V
    // phase; the DC rectifier also has conversion losses. Using separate
    // factors prevents a released-but-not-yet-drawn target from exceeding a
    // single phase at the grid connection point.
    private static final double DC_KW_PER_GRID_A = 0.60d;
    // 0.20 kW/A also covers -10% voltage and a non-ideal power factor instead
    // of assuming exactly 230 V at unity PF.
    private static final double AC_KW_PER_GRID_A = 0.20d;

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

    static Targets redistributeForDemand(Targets fair,
                                         int actualDcKw, int actualAcKw,
                                         int commandedDcKw, int commandedAcKw,
                                         boolean dcDemandLimited, boolean acDemandLimited,
                                         int minDcKw, int maxDcKw,
                                         int minAcKw, int maxAcKw,
                                         int demandReserveKw, int transferStepKw) {
        if (fair.dcKw == 0 || fair.acKw == 0 || dcDemandLimited == acDemandLimited) {
            return fair;
        }

        demandReserveKw = Math.max(1, demandReserveKw);
        transferStepKw = Math.max(1, transferStepKw);

        if (acDemandLimited && actualDcKw >= Math.max(minDcKw, fair.dcKw - 1)) {
            int retainedAcKw = clamp(actualAcKw + demandReserveKw, minAcKw, fair.acKw);
            int transferableKw = fair.acKw - retainedAcKw;
            int proposedDcKw = Math.min(maxDcKw, fair.dcKw + transferableKw);
            int targetDcKw = Math.min(proposedDcKw,
                Math.max(fair.dcKw, commandedDcKw) + transferStepKw);
            int acceptedKw = targetDcKw - fair.dcKw;
            return new Targets(targetDcKw, fair.acKw - acceptedKw);
        }

        if (dcDemandLimited && actualAcKw >= Math.max(minAcKw, fair.acKw - 1)) {
            int retainedDcKw = clamp(actualDcKw + demandReserveKw, minDcKw, fair.dcKw);
            int transferableKw = fair.dcKw - retainedDcKw;
            int proposedAcKw = Math.min(maxAcKw, fair.acKw + transferableKw);
            int targetAcKw = Math.min(proposedAcKw,
                Math.max(fair.acKw, commandedAcKw) + transferStepKw);
            int acceptedKw = targetAcKw - fair.acKw;
            return new Targets(fair.dcKw - acceptedKw, targetAcKw);
        }

        return fair;
    }

    /**
     * A kW shifted from balanced DC to potentially single-phase AC consumes
     * more current on the critical phase. Preserve as much demand transfer as
     * possible without invalidating the projection already proven for the fair
     * allocation.
     */
    static Targets constrainDemandTransfer(Targets fair, Targets transferred,
                                           double measuredCriticalA,
                                           int actualDcKw, int actualAcKw,
                                           double commandCeilingA) {
        int dcKw = transferred.dcKw;
        int acKw = transferred.acKw;
        while (projectedCurrentA(measuredCriticalA, new Targets(dcKw, acKw),
                                 actualDcKw, actualAcKw) > commandCeilingA) {
            if (acKw > fair.acKw && dcKw < fair.dcKw) {
                acKw--;
                dcKw++;
            } else if (dcKw > fair.dcKw && acKw < fair.acKw) {
                dcKw--;
                acKw++;
            } else {
                // The fair allocation was already projection-checked. This is
                // defensive for inconsistent callers or rapidly changed input.
                return fair;
            }
        }
        return new Targets(dcKw, acKw);
    }

    /**
     * Calculate non-authorizing minimum limits for outputs which are currently
     * idle. The projection includes all already released active power. When
     * both outputs are idle and both are eligible, either both minimums fit or
     * neither is pre-armed, preserving equal start priority.
     */
    static Targets safePrearm(boolean dcActive, boolean acActive,
                              int activeDcTargetKw, int activeAcTargetKw,
                              int actualDcKw, int actualAcKw,
                              boolean dcEligible, boolean acEligible,
                              int minDcKw, int minAcKw,
                              double criticalA, double commandCeilingA) {
        int prearmDcKw = !dcActive && dcEligible ? minDcKw : 0;
        int prearmAcKw = !acActive && acEligible ? minAcKw : 0;
        if (prearmDcKw == 0 && prearmAcKw == 0) return new Targets(0, 0);

        Targets possible = new Targets(
            dcActive ? Math.max(0, activeDcTargetKw) : prearmDcKw,
            acActive ? Math.max(0, activeAcTargetKw) : prearmAcKw);
        if (projectedCurrentA(criticalA, possible,
                dcActive ? actualDcKw : 0,
                acActive ? actualAcKw : 0) <= commandCeilingA) {
            return new Targets(prearmDcKw, prearmAcKw);
        }

        // Do not silently favour AC or DC when both idle outputs asked for the
        // same start opportunity but their combined technical minima do not fit.
        if (!dcActive && !acActive && prearmDcKw > 0 && prearmAcKw > 0) {
            return new Targets(0, 0);
        }
        return new Targets(0, 0);
    }

    /** Keep a newly observed session at its technical minimum while it settles. */
    static Targets constrainStartupSettling(Targets target,
                                            boolean dcSettling,
                                            boolean acSettling,
                                            int minDcKw, int minAcKw) {
        int dcKw = dcSettling && target.dcKw > 0
            ? Math.min(target.dcKw, minDcKw) : target.dcKw;
        int acKw = acSettling && target.acKw > 0
            ? Math.min(target.acKw, minAcKw) : target.acKw;
        return new Targets(dcKw, acKw);
    }

    static double projectedCurrentA(double measuredCriticalA, Targets targets,
                                    int actualDcKw, int actualAcKw) {
        int unreachedDcKw = Math.max(0, targets.dcKw - actualDcKw);
        int unreachedAcKw = Math.max(0, targets.acKw - actualAcKw);
        return measuredCriticalA
            + unreachedDcKw / DC_KW_PER_GRID_A
            + unreachedAcKw / AC_KW_PER_GRID_A;
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
