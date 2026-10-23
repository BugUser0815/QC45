package de.rothner.qc45;

import java.io.*;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class IntegrationHealthTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void readinessIsPublishedOnlyAfterBootstrapAndTracksServiceHealth() throws Exception {
        File jar = temporary.newFile("integration.jar");
        FileOutputStream out = new FileOutputStream(jar);
        try { out.write(new byte[] { 'a', 'b', 'c' }); } finally { out.close(); }
        File status = new File(temporary.getRoot(), "health.properties");
        IntegrationHealth health = new IntegrationHealth(status, jar, "current-boot", "123");
        health.publish();
        assertEquals("starting", read(status).getProperty("state"));
        final AtomicBoolean healthy = new AtomicBoolean(true);
        health.ready(new IntegrationHealth.Check() { public boolean healthy() { return healthy.get(); } });
        Properties p = read(status);
        assertEquals("ready", p.getProperty("state"));
        assertEquals("current-boot", p.getProperty("bootId"));
        assertEquals("123", p.getProperty("pid"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", p.getProperty("jarSha256"));
        assertTrue(Long.parseLong(p.getProperty("updatedAtMillis")) > 0L);
        healthy.set(false); health.publish();
        assertEquals("degraded", read(status).getProperty("state"));
        healthy.set(true); health.degraded(); health.publish();
        assertEquals("degraded", read(status).getProperty("state"));
        health.shutdown(); health.publish();
        assertEquals("stopped", read(status).getProperty("state"));
        assertFalse(new File(status.getPath() + ".tmp").exists());
    }

    @Test public void missingJarCannotProduceReadyStatus() throws Exception {
        try {
            new IntegrationHealth(new File(temporary.getRoot(), "health.properties"),
                new File(temporary.getRoot(), "missing.jar"), "boot", "123");
            fail("Missing binary must not receive a readiness identity");
        } catch (FileNotFoundException expected) {}
    }

    @Test public void failingServiceProbePublishesDegradedInsteadOfRetainingReady() throws Exception {
        File jar = temporary.newFile("integration.jar");
        File status = new File(temporary.getRoot(), "health.properties");
        IntegrationHealth health = new IntegrationHealth(status, jar, "boot", "123");
        health.ready(new IntegrationHealth.Check() {
            public boolean healthy() { throw new IllegalStateException("probe failed"); }
        });
        assertEquals("degraded", read(status).getProperty("state"));
        health.shutdown();
    }

    private static Properties read(File file) throws Exception {
        Properties p = new Properties(); FileInputStream in = new FileInputStream(file);
        try { p.load(in); } finally { in.close(); } return p;
    }
}
