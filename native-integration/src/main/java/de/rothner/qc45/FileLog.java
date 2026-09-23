package de.rothner.qc45;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Routes native QC45 integration stdout/stderr to one compact persistent logfile. */
public final class FileLog {
    private static final long ROTATE_BYTES = 10L * 1024L * 1024L;
    private static final int ROTATE_FILES = 3;
    private static boolean installed;
    private static PrintStream fileStream;
    private static PrintStream originalOut;
    private static PrintStream originalErr;
    private static PrintStream installedOut;
    private static PrintStream installedErr;

    private FileLog() {}

    public static synchronized void install(String path) throws IOException {
        if (installed) return;

        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create log directory: " + parent);
        }
        rotate(file);

        fileStream = new PrintStream(new TimestampedOutputStream(new FileOutputStream(file, true)), true, "UTF-8");
        originalOut = System.out;
        originalErr = System.err;
        installedOut = new PrintStream(new TeeOutputStream(originalOut, fileStream), true, "UTF-8");
        installedErr = new PrintStream(new TeeOutputStream(originalErr, fileStream), true, "UTF-8");

        System.setOut(installedOut);
        System.setErr(installedErr);
        installed = true;

        System.out.println("[QC45] ------------------------------------------------------------");
        System.out.println("[QC45] file logging started -> " + file.getAbsolutePath());
    }

    public static synchronized void shutdown() {
        if (!installed) return;
        try { if (System.out == installedOut && originalOut != null) System.setOut(originalOut); }
        catch (Throwable ignored) {}
        try { if (System.err == installedErr && originalErr != null) System.setErr(originalErr); }
        catch (Throwable ignored) {}
        try { if (installedOut != null) installedOut.flush(); } catch (Throwable ignored) {}
        try { if (installedErr != null) installedErr.flush(); } catch (Throwable ignored) {}
        try { if (fileStream != null) fileStream.flush(); } catch (Throwable ignored) {}
        try { if (fileStream != null) fileStream.close(); } catch (Throwable ignored) {}
        fileStream = null;
        installedOut = null;
        installedErr = null;
        originalOut = null;
        originalErr = null;
        installed = false;
    }

    /** UTC timestamps are added to the file only; Tomcat's original console output is unchanged. */
    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static void rotate(File file) throws IOException {
        if (!file.exists() || file.length() < ROTATE_BYTES) return;
        File oldest = new File(file.getPath() + "." + ROTATE_FILES);
        if (oldest.exists() && !oldest.delete()) {
            throw new IOException("Cannot remove old log: " + oldest);
        }
        for (int i = ROTATE_FILES - 1; i >= 1; i--) {
            File from = new File(file.getPath() + "." + i);
            File to = new File(file.getPath() + "." + (i + 1));
            if (from.exists() && !from.renameTo(to)) {
                throw new IOException("Cannot rotate log " + from + " -> " + to);
            }
        }
        File first = new File(file.getPath() + ".1");
        if (!file.renameTo(first)) throw new IOException("Cannot rotate log " + file);
    }

    /**
     * Prefix each physical line, including stack traces and lines split across writes.
     * Keep the line state on the shared file stream so stdout and stderr use the same clock.
     */
    private static final class TimestampedOutputStream extends OutputStream {
        private final OutputStream target;
        private boolean lineStart = true;

        TimestampedOutputStream(OutputStream target) {
            this.target = target;
        }

        public synchronized void write(int value) throws IOException {
            if (lineStart) writePrefix();
            target.write(value);
            if (value == '\\n') lineStart = true;
        }

        public synchronized void write(byte[] bytes, int off, int len) throws IOException {
            if (bytes == null) throw new NullPointerException();
            if (off < 0 || len < 0 || off > bytes.length - len) {
                throw new IndexOutOfBoundsException();
            }
            int end = off + len;
            while (off < end) {
                if (lineStart) writePrefix();
                int next = off;
                while (next < end && bytes[next] != '\\n') next++;
                if (next < end) next++;
                target.write(bytes, off, next - off);
                lineStart = bytes[next - 1] == '\\n';
                off = next;
            }
        }

        public synchronized void flush() throws IOException {
            target.flush();
        }

        public synchronized void close() throws IOException {
            target.close();
        }

        private void writePrefix() throws IOException {
            byte[] prefix = (timestamp() + " ").getBytes("UTF-8");
            target.write(prefix);
            lineStart = false;
        }
    }

    /** Byte-level tee so every PrintStream overload and stack trace is preserved. */
    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream first;
        private final OutputStream second;

        TeeOutputStream(OutputStream first, OutputStream second) {
            this.first = first;
            this.second = second;
        }

        public synchronized void write(int value) throws IOException {
            first.write(value);
            second.write(value);
        }

        public synchronized void write(byte[] bytes, int off, int len) throws IOException {
            first.write(bytes, off, len);
            second.write(bytes, off, len);
        }

        public synchronized void flush() throws IOException {
            first.flush();
            second.flush();
        }
    }
}
