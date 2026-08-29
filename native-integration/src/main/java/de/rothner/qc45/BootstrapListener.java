package de.rothner.qc45;

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

            // The stock configuration has AC.load.balance.enabled=false. In
            // that mode EVCSD accepts maxPowerAC changes in Java but omits the
            // actual max-power payload from normal AC MobiBus messages. Enable
            // the firmware's own positive-limit transport after Integration has
            // established its fail-closed initial targets.
            AcLoadBalanceMode.enableRequired();

            // Zero has different semantics on the original Type2 protocol. The
            // stock load-shed code never sends a 0 kW limit and the satellite
            // has an explicit SUSPEND_CHARGE command. Keep a dedicated transport
            // alongside EVCSD so 0 kW really pauses AC and a later positive
            // release resumes through the native START_CHARGE packet.
            acPowerLimitTransport = AcPowerLimitTransport.startRequired(integration);

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
