package de.rothner.qc45;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.PrintStream;

import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class FileLogTest {
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
