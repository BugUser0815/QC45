package de.rothner.qc45;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Routes native QC45 integration stdout/stderr to one persistent logfile.
 *
 * Installed by BootstrapListener before Integration starts, so LoadManager,
 * ReflectionQC45, GridFailback, OCPP and all diagnostics use the same file.
 * We intentionally do not depend on Tomcat/EVCSD stdout because some QC45
 * installations discard or redirect those streams.
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

        fileStream = new PrintStream(new FileOutputStream(file, true), true, "UTF-8");

        // From this point on every System.out/System.err message produced by the
        // native integration goes directly into the persistent integration log.
        System.setOut(fileStream);
        System.setErr(fileStream);
        installed = true;

        System.out.println("[QC45] ------------------------------------------------------------");
        System.out.println("[QC45] file logging started " + timestamp() + " -> " + file.getAbsolutePath());
        System.out.println("[QC45] stdout/stderr redirected to persistent integration log");
    }

    public static synchronized void info(String message) {
        if (fileStream != null) fileStream.println(message);
    }

    public static synchronized void error(String message) {
        if (fileStream != null) fileStream.println(message);
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
