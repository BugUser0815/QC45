package de.rothner.qc45;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/** Starts/stops the native integration with the existing EVCSD web application. */
public final class BootstrapListener implements ServletContextListener {
    private volatile Integration integration;

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
            // the same native satellite setpoint approach as DC.
            AcLoadBalanceMode.enableRequired();

            // Intentionally do not start AcPowerLimitTransport. LoadManager and
            // ChargingLimitCoordinator already write each dynamic AC target to
            // Configuration.maxPowerAC and SatelliteModule.setMaxPower(). The
            // stock EVCSD owns MobiBus serialization. Our reverse-engineered
            // explicit ENERGY packet caused the BMW i3 to stop charging as soon
            // as the first live power update was sent.
            System.out.println("[QC45] AC native setpoint mode active: dynamic satelliteMaxPower/maxPowerAC, own ENERGY transport disabled");

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
        Integration current = integration;
        integration = null;
        if (current != null) current.stop();

        CcsFullRxTracer.shutdown();
        CcsRawTracerV2.shutdown();
        FileLog.shutdown();
    }
}
