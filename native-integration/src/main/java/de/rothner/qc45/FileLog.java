package de.rothner.qc45;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Routes native QC45 integration stdout/stderr to one compact persistent logfile. */
public final class FileLog {
    private static boolean installed;
    private static PrintStream fileStream;

    private FileLog() {}

    public static synchronized void install(String path) throws IOException {
        if (installed) return;

        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        fileStream = new PrintStream(new FileOutputStream(file, true), true, "UTF-8");
        PrintStream filtered = new FilteringPrintStream(fileStream);

        System.setOut(filtered);
        System.setErr(filtered);
        installed = true;

        System.out.println("[QC45] ------------------------------------------------------------");
        System.out.println("[QC45] file logging started " + timestamp() + " -> " + file.getAbsolutePath());
    }

    public static synchronized void info(String message) {
        if (fileStream != null && !isNoise(message)) fileStream.println(message);
    }

    public static synchronized void error(String message) {
        if (fileStream != null) fileStream.println(message);
    }

    private static boolean isNoise(String line) {
        if (line == null) return false;
        return line.startsWith("[QC45] LoadManager DIAG")
            || line.startsWith("[QC45] LoadManager CCS-CURRENT-REFRESH")
            || line.startsWith("[QC45] LoadManager DC L1=")
            || line.startsWith("[QC45] LoadManager IDLE L1=")
            || line.startsWith("[QC45] LoadManager DC+AC L1=")
            || line.startsWith("[QC45] LoadManager AC L1=")
            || line.startsWith("[QC45] CCS current target")
            || line.startsWith("[QC45] NativeLimit connector=")
            || line.startsWith("[QC45] Grid L1=");
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    /**
     * Keeps errors and state changes, drops only known high-frequency diagnostic lines.
     * The underlying stream remains the single persistent qc45-integration.log file.
     */
    private static final class FilteringPrintStream extends PrintStream {
        private final PrintStream target;

        FilteringPrintStream(PrintStream target) {
            super(new NullOutputStream(), true);
            this.target = target;
        }

        public void println(String value) {
            if (!isNoise(value)) target.println(value);
        }

        public void println(Object value) {
            String line = String.valueOf(value);
            if (!isNoise(line)) target.println(line);
        }

        public void println() {
            target.println();
        }

        public void print(String value) {
            target.print(value);
        }

        public void print(Object value) {
            target.print(value);
        }

        public void write(byte[] buf, int off, int len) {
            target.write(buf, off, len);
        }

        public void write(int b) {
            target.write(b);
        }

        public void flush() {
            target.flush();
        }
    }

    private static final class NullOutputStream extends OutputStream {
        public void write(int b) {}
    }
}
