package de.rothner.qc45;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Duplicates JVM stdout/stderr to a persistent QC45 integration log while
 * preserving the original Tomcat/EVCSD streams.
 *
 * This is intentionally installed from BootstrapListener before Integration
 * starts, so messages from OCPP, loopback, load manager and failback are all
 * visible even on QC45 installations where Tomcat stdout is discarded.
 */
public final class FileLog {
    private static boolean installed;
    private static PrintStream fileStream;

    private FileLog() {}

    public static synchronized void install(String path) throws IOException {
        if (installed) return;

        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        final PrintStream originalOut = System.out;
        final PrintStream originalErr = System.err;
        fileStream = new PrintStream(new FileOutputStream(file, true), true, "UTF-8");

        System.setOut(new PrintStream(new TeeOutputStream(originalOut, fileStream), true));
        System.setErr(new PrintStream(new TeeOutputStream(originalErr, fileStream), true));
        installed = true;

        System.out.println("[QC45] ------------------------------------------------------------");
        System.out.println("[QC45] file logging started " + timestamp() + " -> " + file.getAbsolutePath());
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;

        TeeOutputStream(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        public synchronized void write(int value) throws IOException {
            a.write(value);
            b.write(value);
        }

        public synchronized void write(byte[] data, int off, int len) throws IOException {
            a.write(data, off, len);
            b.write(data, off, len);
        }

        public synchronized void flush() throws IOException {
            a.flush();
            b.flush();
        }
    }
}
