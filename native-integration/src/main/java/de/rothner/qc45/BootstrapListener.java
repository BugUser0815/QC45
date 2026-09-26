package de.rothner.qc45;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/** Starts/stops the native integration with the existing EVCSD web application. */
public final class BootstrapListener implements ServletContextListener {
    private volatile Integration integration;
    private volatile AcPowerTelemetry acPowerTelemetry;
    private volatile AcFixedPowerBridge acFixedPowerBridge;

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

            // Type2 is configured by the operator in the stock QC45. Do not
            // start the bridge that overwrites ACMaxPowerFixed/maxPowerAC or
            // the original AC load-balance selector every 100 ms.

            // Inventory the stock Efacec AC implementation without invoking any
            // candidate methods. The log gives us the exact runtime method/field
            // surface and the source JAR locations for further diagnostics while
            // leaving charging behaviour unchanged.
            AcNativeIntrospector.dumpOnce();

            // Keep the former custom MobiBus writer disabled. Stock EVCSD owns
            // START_CHARGE/ENERGY serialization. We only derive live AC power
            // from the energy counter for LoadManager/UI telemetry.
            acPowerTelemetry = AcPowerTelemetry.startRequired();
            System.out.println("[QC45] AC operator-fixed mode: integration AC power writes/stops disabled; stock START_CHARGE/ENERGY, telemetry=energy-delta");

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
            AcPowerTelemetry telemetry = acPowerTelemetry;
            acPowerTelemetry = null;
            if (telemetry != null) {
                try {
                    telemetry.shutdown();
                    telemetry.join(1000L);
                } catch (Throwable stopError) {
                    System.err.println("[QC45] failed AC telemetry cleanup: " + stopError);
                }
            }

            AcFixedPowerBridge bridge = acFixedPowerBridge;
            acFixedPowerBridge = null;
            if (bridge != null) {
                try {
                    bridge.shutdown();
                    bridge.join(1000L);
                } catch (Throwable stopError) {
                    System.err.println("[QC45] failed AC fixed-limit bridge cleanup: " + stopError);
                }
            }

            Integration degraded = integration;
            if (degraded != null) {
                // Integration.start() has already installed the independent
                // limit guard. Keep it alive: stopping the integration here
                // would let stock EVCSD restore unsafe positive limits after a
                // required AC native-setpoint setup failure.
                degraded.enterPersistentDegradedSafety(
                    "required AC native setpoint setup failed", e);
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

    public void contextDestroyed(ServletContextEvent event) {
        AcPowerTelemetry telemetry = acPowerTelemetry;
        acPowerTelemetry = null;
        if (telemetry != null) {
            telemetry.shutdown();
            try { telemetry.join(1000L); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }

        AcFixedPowerBridge bridge = acFixedPowerBridge;
        acFixedPowerBridge = null;
        if (bridge != null) {
            bridge.shutdown();
            try { bridge.join(1000L); }
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
