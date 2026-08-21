package de.rothner.qc45;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Properties;

/**
 * Test-only runtime override for the live CCS SatelliteStation metadata.
 *
 * EVCSD SatelliteModule.retrieveQuickInformation() initializes quick-charge
 * connectors with 125 A / 50000 W and explicitly persists those values during
 * SatelliteModule.init(). This override intentionally runs afterwards and does
 * not call Database.updateEntity() or Configuration.updateValue().
 */
public final class CcsHardwareOverride {
    private CcsHardwareOverride() {}

    public static void apply() throws Exception {
        Properties p = loadProperties();
        String rawCurrent = p.getProperty("evcsd.ccsHardwareMaxCurrentA");
        String rawPower = p.getProperty("evcsd.ccsHardwareMaxPowerW");
        if ((rawCurrent == null || rawCurrent.trim().length() == 0)
                && (rawPower == null || rawPower.trim().length() == 0)) {
            System.out.println("[QC45] EVCSD CCS hardware metadata override disabled");
            return;
        }

        Integer currentA = rawCurrent == null || rawCurrent.trim().length() == 0
            ? null : Integer.valueOf(Integer.parseInt(rawCurrent.trim()));
        Integer powerW = rawPower == null || rawPower.trim().length() == 0
            ? null : Integer.valueOf(Integer.parseInt(rawPower.trim()));

        if (currentA != null && (currentA.intValue() < 1 || currentA.intValue() > 125)) {
            throw new IllegalArgumentException("evcsd.ccsHardwareMaxCurrentA must be 1..125");
        }
        if (powerW != null && (powerW.intValue() < 1000 || powerW.intValue() > 50000)) {
            throw new IllegalArgumentException("evcsd.ccsHardwareMaxPowerW must be 1000..50000");
        }

        Class<?> centralType = Class.forName("pt.efacec.es.mobie.agent.statemachines.CentralModule");
        Object central = centralType.getMethod("getCurrentModule").invoke(null);
        if (central == null) throw new IllegalStateException("CentralModule unavailable for CCS hardware override");
        Object[] satellites = (Object[])centralType.getMethod("getSatellites").invoke(central);
        if (satellites == null) throw new IllegalStateException("Satellites unavailable for CCS hardware override");

        for (int i = 0; i < satellites.length; i++) {
            Object sat = satellites[i];
            if (sat == null) continue;
            int connector = ((Number)sat.getClass().getMethod("getSatelliteId").invoke(sat)).intValue();
            if (connector != 2) continue;

            Object info = sat.getClass().getMethod("getSatelliteInfoDB").invoke(sat);
            if (info == null) throw new IllegalStateException("Connector 2 SatelliteStation unavailable");

            Method getAmperage = info.getClass().getMethod("getAmperage");
            Method setAmperage = info.getClass().getMethod("setAmperage", Double.class);
            Method getPower = info.getClass().getMethod("getPower");
            Method setPower = info.getClass().getMethod("setPower", Double.class);

            Double oldA = (Double)getAmperage.invoke(info);
            Double oldW = (Double)getPower.invoke(info);

            if (currentA != null) setAmperage.invoke(info, Double.valueOf(currentA.doubleValue()));
            if (powerW != null) setPower.invoke(info, Double.valueOf(powerW.doubleValue()));

            Double effectiveA = (Double)getAmperage.invoke(info);
            Double effectiveW = (Double)getPower.invoke(info);
            if (currentA != null && (effectiveA == null || Math.round(effectiveA.doubleValue()) != currentA.intValue())) {
                throw new IllegalStateException("CCS hardware current override did not stick: " + effectiveA);
            }
            if (powerW != null && (effectiveW == null || Math.round(effectiveW.doubleValue()) != powerW.intValue())) {
                throw new IllegalStateException("CCS hardware power override did not stick: " + effectiveW);
            }

            System.out.println("[QC45] EVCSD CCS hardware metadata connector=2"
                + " amperage=" + format(oldA) + "A->" + format(effectiveA) + "A"
                + " power=" + format(oldW) + "W->" + format(effectiveW) + "W"
                + " runtime object only; no Database.updateEntity call");
            return;
        }

        throw new IllegalStateException("CCS connector 2 unavailable for CCS hardware override");
    }

    private static String format(Double value) {
        return value == null ? "n/a" : String.valueOf(Math.round(value.doubleValue()));
    }

    private static Properties loadProperties() throws Exception {
        Properties p = new Properties();
        String explicit = System.getProperty("qc45.integration.config");
        File file = explicit == null || explicit.trim().length() == 0
            ? new File("/home/mobie/evcsd/qc45-integration.properties")
            : new File(explicit.trim());
        InputStream in = new FileInputStream(file);
        try { p.load(in); } finally { in.close(); }
        return p;
    }
}
