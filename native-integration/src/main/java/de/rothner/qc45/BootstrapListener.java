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
            // that mode EVCSD accepts maxPowerAC changes in Java but omits the
            // actual max-power payload from normal AC MobiBus messages. Enable
            // the firmware's own transport after Integration has established
            // its fail-closed initial targets.
            AcLoadBalanceMode.enableRequired();

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
            Integration failed = integration;
            integration = null;
            if (failed != null) {
                try { failed.stop(); }
                catch (Throwable stopError) {
                    System.err.println("[QC45] failed integration cleanup error: " + stopError);
                }
            }
            System.err.println("[QC45] native integration failed to start: " + e);
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
