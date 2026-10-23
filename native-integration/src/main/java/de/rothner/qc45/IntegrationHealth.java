package de.rothner.qc45;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.util.Properties;

/** Boot/process-specific deployment status, independent of rotating logs. */
final class IntegrationHealth {
    static final String DEFAULT_PATH = "/home/mobie/evcsd/qc45-integration-health.properties";
    interface Check { boolean healthy(); }
    private final File file;
    private final String jarSha256;
    private final String bootId;
    private final String pid;
    private final Thread worker;
    private volatile boolean running = true;
    private volatile boolean complete;
    private volatile boolean failed;
    private volatile Check check;
    private volatile long lastError;

    static IntegrationHealth startDefault() throws Exception {
        File jar = new File(BootstrapListener.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String process = ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0];
        IntegrationHealth health = new IntegrationHealth(new File(DEFAULT_PATH), jar,
            readLine(new File("/proc/sys/kernel/random/boot_id")), process);
        health.publishBestEffort();
        health.worker.start();
        return health;
    }

    IntegrationHealth(File file, File jar, String bootId, String pid) throws Exception {
        this.file = file;
        this.jarSha256 = digest(jar);
        this.bootId = bootId;
        this.pid = pid;
        worker = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    publishBestEffort();
                    try { Thread.sleep(5000L); }
                    catch (InterruptedException ignored) {}
                }
            }
        }, "QC45-Integration-Health");
        worker.setDaemon(true);
    }

    void ready(Check check) {
        this.check = check;
        complete = true;
        publishBestEffort();
    }

    void degraded() { failed = true; publishBestEffort(); }

    void shutdown() {
        running = false;
        worker.interrupt();
        publishBestEffort();
    }

    synchronized void publish() throws Exception {
        String state = !running ? "stopped" : failed ? "degraded" : !complete ? "starting" : "degraded";
        if (running && complete && !failed && check != null) {
            try { if (check.healthy()) state = "ready"; }
            catch (RuntimeException unavailable) { state = "degraded"; }
        }
        Properties p = new Properties();
        p.setProperty("schema", "1");
        p.setProperty("state", state);
        p.setProperty("bootId", bootId);
        p.setProperty("pid", pid);
        p.setProperty("jarSha256", jarSha256);
        p.setProperty("updatedAtMillis", String.valueOf(System.currentTimeMillis()));
        File temp = new File(file.getPath() + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(temp);
            try { p.store(out, "QC45 native integration health"); }
            finally { out.close(); }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp.toPath()); }
    }

    private void publishBestEffort() {
        try { publish(); }
        catch (Exception error) {
            long now = System.currentTimeMillis();
            if (now - lastError >= 5000L) {
                System.err.println("[QC45] integration health status unavailable: " + error);
                lastError = now;
            }
        }
    }

    private static String readLine(File file) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        try {
            String value = in.readLine();
            if (value == null || value.trim().length() == 0) throw new IOException("Missing boot identity: " + file);
            return value.trim();
        } finally { in.close(); }
    }

    private static String digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] bytes = new byte[8192]; int count;
            while ((count = in.read(bytes)) >= 0) digest.update(bytes, 0, count);
        } finally { in.close(); }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return hex.toString();
    }
}
