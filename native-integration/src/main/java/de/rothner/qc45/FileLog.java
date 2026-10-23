package de.rothner.qc45;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
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
        fileStream = new PrintStream(new TimestampedOutputStream(file, ROTATE_BYTES, ROTATE_FILES), true, "UTF-8");
        originalOut = System.out;
        originalErr = System.err;
        installedOut = new PrintStream(new TeeOutputStream(originalOut, fileStream), true, "UTF-8");
        installedErr = new PrintStream(new TeeOutputStream(originalErr, fileStream), true, "UTF-8");

        System.setOut(installedOut);
        System.setErr(installedErr);
        installed = true;

        System.out.println("[QC45] ------------------------------------------------------------");
        System.out.println("[QC45] file logging started -> " + file.getAbsolutePath());
        System.out.println("[QC45] log retention active maxBytes=" + ROTATE_BYTES
            + " backups=" + ROTATE_FILES + " rotation=runtime");
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

    private static void rotate(File file, int backups) throws IOException {
        File oldest = new File(file.getPath() + "." + backups);
        if (oldest.exists() && !oldest.delete()) {
            throw new IOException("Cannot remove old log: " + oldest);
        }
        for (int i = backups - 1; i >= 1; i--) {
            File from = new File(file.getPath() + "." + i);
            File to = new File(file.getPath() + "." + (i + 1));
            if (from.exists() && !from.renameTo(to)) {
                throw new IOException("Cannot rotate log " + from + " -> " + to);
            }
        }
        File first = new File(file.getPath() + ".1");
        if (!file.renameTo(first)) throw new IOException("Cannot rotate log " + file);
    }

    /** Reclaim space from pre-upgrade logs without allocating another large file. */
    private static void retainTail(File file, long limit) throws IOException {
        if (!file.isFile() || file.length() <= limit) return;
        RandomAccessFile contents = new RandomAccessFile(file, "rw");
        try {
            long source = contents.length() - limit;
            long destination = 0L;
            byte[] buffer = new byte[8192];
            while (destination < limit) {
                contents.seek(source);
                int count = contents.read(buffer, 0, (int)Math.min(buffer.length, limit - destination));
                if (count < 0) throw new IOException("Unexpected end of log: " + file);
                contents.seek(destination);
                contents.write(buffer, 0, count);
                source += count;
                destination += count;
            }
            contents.setLength(limit);
        } finally {
            contents.close();
        }
    }

    /**
     * Prefix each physical line, including stack traces and lines split across writes.
     * Keep the line state on the shared file stream so stdout and stderr use the same clock.
     */
    static final class TimestampedOutputStream extends OutputStream {
        private final File file;
        private final long limit;
        private final int backups;
        private OutputStream target;
        private long size;
        private boolean lineStart = true;
        private boolean closed;

        TimestampedOutputStream(File file, long limit, int backups) throws IOException {
            if (limit < 64L || backups < 1) throw new IllegalArgumentException("Invalid log retention limits");
            this.file = file;
            this.limit = limit;
            this.backups = backups;
            // The previous implementation only rotated at startup, so any of
            // these files may already be much larger than the configured limit.
            for (int i = 0; i <= backups; i++) {
                retainTail(i == 0 ? file : new File(file.getPath() + "." + i), limit);
            }
            if (file.length() >= limit) rotate(file, backups);
            open();
        }

        public synchronized void write(int value) throws IOException {
            write(new byte[] {(byte)value}, 0, 1);
        }

        public synchronized void write(byte[] bytes, int off, int len) throws IOException {
            if (bytes == null) throw new NullPointerException();
            if (off < 0 || len < 0 || off > bytes.length - len) {
                throw new IndexOutOfBoundsException();
            }
            int end = off + len;
            while (off < end) {
                if (closed) throw new IOException("Log stream is closed");
                if (target == null) open();
                int next = off;
                while (next < end && bytes[next] != '\n') next++;
                if (next < end) next++;
                if (size >= limit) rotateOpenFile();
                if (lineStart) {
                    byte[] prefix = (timestamp() + " ").getBytes("UTF-8");
                    // Keep ordinary lines together; split exceptionally long
                    // lines rather than allowing a single write to fill the card.
                    long needed = prefix.length + Math.min((long)(next - off), limit - prefix.length);
                    if (size > 0L && limit - size < needed) rotateOpenFile();
                    append(prefix, 0, prefix.length);
                    lineStart = false;
                }
                int count = (int)Math.min((long)(next - off), limit - size);
                append(bytes, off, count);
                lineStart = bytes[off + count - 1] == '\n';
                off += count;
            }
        }

        public synchronized void flush() throws IOException {
            if (target != null) target.flush();
        }

        public synchronized void close() throws IOException {
            closed = true;
            if (target != null) target.close();
            target = null;
        }

        private void open() throws IOException {
            target = new FileOutputStream(file, true);
            size = file.length();
        }

        private void append(byte[] bytes, int off, int len) throws IOException {
            try {
                target.write(bytes, off, len);
                size += len;
            } catch (IOException error) {
                // A full filesystem can accept part of a write before failing.
                // Account for those bytes before a later write is attempted.
                size = file.length();
                throw error;
            }
        }

        private void rotateOpenFile() throws IOException {
            target.close();
            target = null;
            try {
                rotate(file, backups);
            } catch (IOException error) {
                // A failed rename/delete must never fall back to unbounded
                // appending. Reopen for a later retry, then reject this write.
                open();
                throw error;
            }
            open();
            lineStart = true;
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
