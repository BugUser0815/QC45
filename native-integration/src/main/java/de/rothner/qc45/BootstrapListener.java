package de.rothner.qc45;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/** Starts/stops the native integration with the existing EVCSD web application. */
public final class BootstrapListener implements ServletContextListener {
    private volatile Integration integration;
    private volatile AcPowerTelemetry acPowerTelemetry;

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

            // The stock configuration has AC.load.balance.enabled=false. In
            // that mode EVCSD accepts maxPowerAC / satelliteMaxPower changes in
            // Java but does not serialize the normal Type2 power limit. Enable
            // the original Efacec AC load-balance path so LoadManager can use
            // the native satellite setpoint state without our own ENERGY packet.
            AcLoadBalanceMode.enableRequired();

            // Inventory the stock Efacec AC implementation without invoking any
            // candidate methods. The log gives us the exact runtime method/field
            // surface and the source JAR locations for the next reverse-engineering
            // step while leaving charging behaviour unchanged.
            AcNativeIntrospector.dumpOnce();

            // Keep the former MobiBus writer disabled. Its explicit ENERGY
            // request reproducibly stopped the BMW i3. We still need the
            // read-only energy-delta sampler, because the old EVCSD leaves the
            // Type2 infoState.power field at zero while charging. Without that
            // telemetry LoadManager/UI incorrectly report Netz-Pause / 0 kW.
            acPowerTelemetry = AcPowerTelemetry.startRequired();
            System.out.println("[QC45] AC native setpoint mode active: dynamic satelliteMaxPower/maxPowerAC, own ENERGY transport disabled, telemetry=energy-delta");

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

        Integration current = integration;
        integration = null;
        if (current != null) current.stop();

        CcsFullRxTracer.shutdown();
        CcsRawTracerV2.shutdown();
        FileLog.shutdown();
    }
}
