package de.rothner.qc45;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.PrintStream;
import java.io.FileOutputStream;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class FileLogTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void loggingTeesAndRestoresGlobalStreams() throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        File file = File.createTempFile("qc45-file-log-", ".log");
        try {
            FileLog.install(file.getAbsolutePath());
            System.out.println("QC45_FILELOG_STDOUT_MARKER");
            System.err.println("QC45_FILELOG_STDERR_MARKER");
            System.out.print("SPLIT_");
            System.out.print("LINE\nSECOND_LINE\n");
        } finally {
            FileLog.shutdown();
        }

        assertSame(originalOut, System.out);
        assertSame(originalErr, System.err);
        String contents = read(file);
        assertTrue(contents.indexOf("QC45_FILELOG_STDOUT_MARKER") >= 0);
        assertTrue(contents.indexOf("QC45_FILELOG_STDERR_MARKER") >= 0);
        assertTrue(contents.indexOf("SPLIT_LINE") >= 0);
        String[] lines = contents.split("\\r?\\n");
        assertTrue(lines.length >= 5);
        for (String line : lines) {
            assertTrue("Missing ISO UTC timestamp: " + line,
                line.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z .*"));
        }
        file.delete();
    }

    @Test
    public void rotatesDuringContinuousWritesAndDeletesOldestGeneration() throws Exception {
        File file = new File(temporary.getRoot(), "integration.log");
        FileLog.TimestampedOutputStream stream = new FileLog.TimestampedOutputStream(file, 256L, 3);
        try {
            for (int i = 0; i < 100; i++) write(stream, "RECORD_" + i + " some diagnostic data\n");
        } finally {
            stream.close();
        }
        assertBounded(file, 256L);
        assertTrue(new File(file + ".3").isFile());
        assertFalse(new File(file + ".4").exists());
        String retained = "";
        for (int i = 3; i >= 0; i--) retained += read(i == 0 ? file : new File(file + "." + i));
        assertFalse(retained.contains("RECORD_0 "));
        assertTrue(read(file).contains("RECORD_99 "));
        int previous = -1;
        for (String line : retained.split("\n")) {
            assertTrue(line, line.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z RECORD_\\d+ some diagnostic data"));
            int number = Integer.parseInt(line.substring(line.indexOf("RECORD_") + 7, line.indexOf(" some")));
            if (previous >= 0) assertEquals(previous + 1, number);
            previous = number;
        }
        assertEquals(99, previous);
    }

    @Test
    public void boundsOneOversizedWriteAndResumesLogging() throws Exception {
        File file = new File(temporary.getRoot(), "integration.log");
        FileLog.TimestampedOutputStream stream = new FileLog.TimestampedOutputStream(file, 128L, 3);
        byte[] hugeLine = new byte[10000];
        Arrays.fill(hugeLine, (byte)'x');
        try {
            stream.write(hugeLine);
            stream.write('\n');
            write(stream, "AFTER_HUGE_LINE\n");
        } finally {
            stream.close();
        }
        assertBounded(file, 128L);
        assertTrue(read(file).contains("AFTER_HUGE_LINE"));
        for (int i = 0; i <= 3; i++) {
            String text = read(i == 0 ? file : new File(file + "." + i));
            assertTrue(text, text.matches("(?s)^\\d{4}-\\d{2}-\\d{2}T.*"));
        }
    }

    @Test
    public void startupReclaimsOversizedLegacyLogsAndPreservesNewestTail() throws Exception {
        File file = new File(temporary.getRoot(), "integration.log");
        for (int i = 0; i <= 3; i++) {
            File old = i == 0 ? file : new File(file + "." + i);
            FileOutputStream out = new FileOutputStream(old);
            try {
                byte[] bytes = new byte[30000];
                Arrays.fill(bytes, (byte)'a');
                out.write(bytes);
                out.write(("LATEST_GENERATION_" + i + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
        }
        File unrelated = temporary.newFile("native-evcsd.log");
        FileOutputStream out = new FileOutputStream(unrelated);
        out.write("UNTOUCHED".getBytes("UTF-8"));
        out.close();
        FileLog.TimestampedOutputStream stream = new FileLog.TimestampedOutputStream(file, 10000L, 3);
        try { write(stream, "NEW_SESSION\n"); }
        finally { stream.close(); }
        assertBounded(file, 10000L);
        assertTrue(read(new File(file + ".1")).endsWith("LATEST_GENERATION_0\n"));
        assertTrue(read(new File(file + ".3")).endsWith("LATEST_GENERATION_2\n"));
        assertTrue(read(file).contains("NEW_SESSION"));
        assertEquals("UNTOUCHED", read(unrelated));
    }

    @Test
    public void appendsToSmallLogAcrossRestartsAndPreservesSplitLines() throws Exception {
        File file = new File(temporary.getRoot(), "integration.log");
        FileLog.TimestampedOutputStream stream = new FileLog.TimestampedOutputStream(file, 256L, 3);
        write(stream, "BEFORE_RESTART\n");
        stream.close();
        stream = new FileLog.TimestampedOutputStream(file, 256L, 3);
        try {
            write(stream, "SPLIT_");
            write(stream, "LINE\n");
        } finally { stream.close(); }
        String contents = read(file);
        assertTrue(contents.contains("BEFORE_RESTART"));
        assertTrue(contents.contains("SPLIT_LINE"));
        assertFalse(new File(file + ".1").exists());
    }

    @Test
    public void failedRotationDoesNotAppendBeyondLimitAndCanRecover() throws Exception {
        File file = new File(temporary.getRoot(), "integration.log");
        File blocker = temporary.newFolder("integration.log.3");
        File child = new File(blocker, "keep");
        assertTrue(child.createNewFile());
        FileLog.TimestampedOutputStream stream = new FileLog.TimestampedOutputStream(file, 64L, 3);
        try {
            write(stream, "FIRST_RECORD_1234567890\n");
            long size = file.length();
            for (int i = 0; i < 3; i++) {
                try {
                    write(stream, "SECOND_RECORD_1234567890\n");
                    fail("Rotation should fail while the archive is blocked");
                } catch (java.io.IOException expected) {
                    assertEquals(size, file.length());
                    assertTrue(file.length() <= 64L);
                }
            }
            assertTrue(child.delete());
            assertTrue(blocker.delete());
            write(stream, "RECOVERED\n");
        } finally { stream.close(); }
        assertTrue(read(file).contains("RECOVERED"));
        assertTrue(read(new File(file + ".1")).contains("FIRST_RECORD"));
    }

    @Test
    public void concurrentWritersShareRotationAndLineState() throws Exception {
        final File file = new File(temporary.getRoot(), "integration.log");
        final FileLog.TimestampedOutputStream stream = new FileLog.TimestampedOutputStream(file, 512L, 3);
        final Throwable[] errors = new Throwable[2];
        Thread[] threads = new Thread[2];
        for (int i = 0; i < threads.length; i++) {
            final int writer = i;
            threads[i] = new Thread(new Runnable() {
                public void run() {
                    try {
                        for (int n = 0; n < 100; n++) write(stream, "WRITER_" + writer + "_" + n + "\n");
                    } catch (Throwable error) { errors[writer] = error; }
                }
            });
            threads[i].start();
        }
        try {
            for (Thread thread : threads) thread.join(5000L);
            for (Thread thread : threads) assertFalse("Writer did not finish", thread.isAlive());
            for (Throwable error : errors) assertTrue(String.valueOf(error), error == null);
        } finally { stream.close(); }
        assertBounded(file, 512L);
        for (int i = 0; i <= 3; i++) {
            for (String line : read(i == 0 ? file : new File(file + "." + i)).split("\n")) {
                assertTrue(line, line.matches("\\d{4}-\\d{2}-\\d{2}T.*Z WRITER_[01]_\\d+"));
            }
        }
    }

    private static void write(FileLog.TimestampedOutputStream stream, String text) throws Exception {
        stream.write(text.getBytes("UTF-8"));
    }

    private static void assertBounded(File file, long limit) {
        long total = 0L;
        for (int i = 0; i <= 3; i++) {
            File generation = i == 0 ? file : new File(file + "." + i);
            assertTrue(generation + " exceeds limit", generation.length() <= limit);
            total += generation.length();
        }
        assertTrue(total <= 4L * limit);
    }

    private static String read(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
        } finally {
            in.close();
        }
        return new String(out.toByteArray(), "UTF-8");
    }
}
