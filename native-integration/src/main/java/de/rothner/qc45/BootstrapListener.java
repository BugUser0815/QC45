package de.rothner.qc45;

import java.lang.reflect.Field;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/** Starts/stops the native integration with the existing EVCSD web application. */
public final class BootstrapListener implements ServletContextListener {
    private volatile Integration integration;
    private volatile AcPowerLimitTransport acPowerLimitTransport;

    public void contextInitialized(ServletContextEvent event) {
        try {
            FileLog.install("/home/mobie/evcsd/qc45-integration.log");
        } catch (Throwable e) {
            try {
                System.err.println("[QC45] persistent file logging failed: " + e);
                e.printStackTrace();
            } catch (Throwable ignored) {}
        }

        try {
            integration = Integration.start();

            // Diagnostic isolation for the current Type2 problem: keep AC at
            // the proven 5 kW start value so LoadManager cannot request a later
            // 6/7/8 kW step while we verify the stock EVCSD start path.
            forceAcDiagnosticBudget(integration);

            // The stock configuration has AC.load.balance.enabled=false. In
            // that mode EVCSD accepts maxPowerAC changes in Java but omits the
            // actual max-power payload from normal AC MobiBus messages. Enable
            // it so the original START_CHARGE contains the 5 kW pre-armed limit.
            AcLoadBalanceMode.enableRequired();

            // Do NOT start AcPowerLimitTransport in this diagnostic build.
            // The BMW i3 trace showed that charging collapsed immediately after
            // our first explicit ENERGY update. Keeping this transport stopped
            // guarantees that the integration sends no own ENERGY power-limit
            // packet during an active AC session. GridFailback/HardTrip remain
            // inside Integration and can still stop a transaction independently.
            acPowerLimitTransport = null;
            System.out.println("[QC45] AC DIAGNOSTIC MODE active: fixed 5kW, own ENERGY transport disabled");

            event.getServletContext().setAttribute("qc45.native.integration", integration);
            try {
                CcsRawTracerV2.installFromDefaultConfig();
            } catch (Throwable traceError) {
                System.err.println("[QC45] CCS-RAW2 tracer failed to install: " + traceError);
                traceError.printStackTrace();
            }
            try {
                CcsFullRxTracer.installFromDefaultConfig();
            } catch (Throwable traceError) {
                System.err.println("[QC45] CCS-FULL-RX tracer failed to install: " + traceError);
                traceError.printStackTrace();
            }
        } catch (Throwable e) {
            AcPowerLimitTransport transport = acPowerLimitTransport;
            acPowerLimitTransport = null;
            if (transport != null) {
                try {
                    transport.shutdown();
                    transport.join(1000L);
                } catch (Throwable stopError) {
                    System.err.println("[QC45] failed AC transport cleanup error: " + stopError);
                }
            }

            Integration degraded = integration;
            if (degraded != null) {
                // Integration.start() has already installed the independent
                // limit guard. Keep it alive: stopping the integration here
                // would let stock EVCSD restore unsafe positive limits after a
                // required AC setup failure.
                degraded.enterPersistentDegradedSafety(
                    "required AC diagnostic setup failed", e);
                try {
                    event.getServletContext().setAttribute(
                        "qc45.native.integration", degraded);
                } catch (Throwable ignored) {}
            } else {
                System.err.println("[QC45] native integration failed before safety guard startup: " + e);
            }
            System.err.println("[QC45] native integration startup DEGRADED; safety guard remains active: " + e);
            e.printStackTrace();
        }
    }

    private static void forceAcDiagnosticBudget(Integration integration) throws Exception {
        Field field = Integration.class.getDeclaredField("limits");
        field.setAccessible(true);
        Object value = field.get(integration);
        if (!(value instanceof ChargingLimitCoordinator)) {
            throw new IllegalStateException("ChargingLimitCoordinator unavailable");
        }
        ChargingLimitCoordinator limits = (ChargingLimitCoordinator)value;
        limits.requestAcBudget(ChargingLimitCoordinator.AC_NOTLADEN_KW);
        System.out.println("[QC45] AC diagnostic cap="
            + ChargingLimitCoordinator.AC_NOTLADEN_KW + "kW");
    }

    public void contextDestroyed(ServletContextEvent event) {
        AcPowerLimitTransport transport = acPowerLimitTransport;
        acPowerLimitTransport = null;
        if (transport != null) {
            transport.shutdown();
            try { transport.join(1000L); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }

        Integration current = integration;
        integration = null;
        if (current != null) current.stop();

        CcsFullRxTracer.shutdown();
        CcsRawTracerV2.shutdown();
        FileLog.shutdown();
    }
}
