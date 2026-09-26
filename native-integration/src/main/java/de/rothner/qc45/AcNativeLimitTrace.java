package de.rothner.qc45;

import java.lang.reflect.Method;

/** Read-only reconstruction of the stock build 57 normal-AC packet limits. */
final class AcNativeLimitTrace {
    private static final String CENTRAL =
        "pt.efacec.es.mobie.agent.statemachines.CentralModule";

    private AcNativeLimitTrace() {}

    static String snapshot(Object satellite) {
        try {
            Class<?> type = Class.forName(CENTRAL);
            Object central = type.getMethod("getCurrentModule").invoke(null);
            Object conf = type.getMethod("getConf").invoke(central);
            int chargingCount = number(satellite, "getSatsInCharge");
            int acFixed = number(conf, "getACMaxPowerFixed");
            int dcFixed = number(conf, "getDCMaxPowerFixed");
            int maxAc = number(conf, "getMaxPowerAC");
            int satelliteMax = number(satellite, "getMaxPower");
            boolean acBalance = bool(conf, "isEnableACLoadBalance");
            boolean dcBalance = bool(conf, "isLoadBalanceEnabled");
            boolean fixed = acFixed > 0 && dcFixed > 0;

            // SatelliteModule.sendNormalChargeStart(): first divide by the
            // charging count PLUS the satellite about to start. getEnergy()
            // divides by the current charging count WITHOUT a zero guard.
            String startMode = acBalance ? "balance" : fixed ? "fixed" : "legacy";
            String energyMode = startMode;
            String start = acBalance
                ? String.valueOf((int)(((double)maxAc / (chargingCount + 1)) * 10.0d))
                : fixed ? String.valueOf(acFixed * 10) : "0";
            String energy = acBalance
                ? chargingCount == 0 ? "UNBOUNDED(count=0)"
                    : String.valueOf((int)(((double)maxAc / chargingCount) * 10.0d))
                : fixed ? String.valueOf(acFixed * 10) : String.valueOf(satelliteMax);
            return "acBalance=" + acBalance + " dcBalance=" + dcBalance
                + " chargingCount=" + chargingCount + " acFixed=" + acFixed
                + " dcFixed=" + dcFixed + " maxPowerAC=" + maxAc
                + " satelliteMax=" + satelliteMax + " start=" + start
                + "(" + startMode + ") energy=" + energy + "(" + energyMode + ")"
                + " scale=stock-x10 unit=board-unverified";
        } catch (Throwable error) {
            return "unavailable=" + error.getClass().getSimpleName();
        }
    }

    private static int number(Object owner, String name) throws Exception {
        Method method = owner.getClass().getMethod(name);
        return ((Number)method.invoke(owner)).intValue();
    }

    private static boolean bool(Object owner, String name) throws Exception {
        Method method = owner.getClass().getMethod(name);
        return ((Boolean)method.invoke(owner)).booleanValue();
    }
}
