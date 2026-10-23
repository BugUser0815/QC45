package de.rothner.qc45;

import java.util.ArrayDeque;

/** Read-only connection detector and bounded diagnostic pre-roll for the master stream. */
final class CcsTraceSession {
    interface StatusListener { void status(byte[] frame, long now); }
    static final CcsTraceSession CURRENT = new CcsTraceSession(new StatusListener() {
        public void status(byte[] frame, long now) {
            CcsRawTracerV2.observeValidatedStatus(frame, now);
        }
    });
    static final long TAIL_MS = 5000L;
    private static final long STATUS_TIMEOUT_MS = 90000L;
    private final byte[] master = new byte[259];
    private final byte[] quick = new byte[14];
    private int masterLength;
    private int quickLength;
    private long captureUntil;
    private boolean connected;
    private boolean onlyWhenConnected = true;
    private final StatusListener listener;

    CcsTraceSession() { this(null); }
    CcsTraceSession(StatusListener listener) { this.listener = listener; }

    synchronized void configure(boolean connectionOnly) { onlyWhenConnected = connectionOnly; }

    synchronized void reset() {
        masterLength = 0;
        quickLength = 0;
        captureUntil = 0L;
        connected = false;
    }

    synchronized boolean capturing(long now) {
        return !onlyWhenConnected || (captureUntil > 0L && now <= captureUntil);
    }

    synchronized void observeRx(byte[] data, int off, int len, long now) {
        for (int p = off; p < off + len; p++) {
            if (masterLength == 0 && (data[p] & 255) != 0x2a) continue;
            master[masterLength++] = data[p];
            parseMaster(now);
        }
    }

    private void parseMaster(long now) {
        while (masterLength >= 3) {
            int length = (master[2] & 255) + 4;
            if (masterLength < length) return;
            int sum = 0;
            for (int i = 0; i < length; i++) sum += master[i] & 255;
            if ((sum & 255) == 0) {
                // Device 0x34 carries the physical QuickCharge/CCS stream.
                if ((master[1] & 255) == 0x34) {
                    for (int i = 3; i < length - 1; i++) quickByte(master[i], now);
                }
                discardMaster(length);
            } else {
                // A corrupt/missing outer frame also breaks the inner stream.
                quickLength = 0;
                discardMaster(1);
            }
            while (masterLength > 0 && (master[0] & 255) != 0x2a) discardMaster(1);
        }
    }

    private void discardMaster(int count) {
        masterLength -= count;
        System.arraycopy(master, count, master, 0, masterLength);
    }

    private void quickByte(byte value, long now) {
        quick[quickLength++] = value;
        if (quickLength < quick.length) return;
        int header = quick[0] & 255;
        int receivedCrc = (quick[12] & 255) | ((quick[13] & 255) << 8);
        if ((header == 0x62 || header == 0x63) && crc(quick, 12) == receivedCrc) {
            // Cable detection includes the 0 V / 0 A handshake. Repeated idle
            // replies must not keep extending the diagnostic tail indefinitely.
            boolean present = (quick[1] & 0x70) != 0;
            if (present) captureUntil = now + STATUS_TIMEOUT_MS;
            else if (connected) captureUntil = now + TAIL_MS;
            connected = present;
            if (header == 0x63 && listener != null) listener.status(quick, now);
            quickLength = 0;
        } else {
            System.arraycopy(quick, 1, quick, 0, quick.length - 1);
            quickLength--;
        }
    }

    private static int crc(byte[] bytes, int length) {
        int crc = 0xffff;
        for (int i = 0; i < length; i++) {
            crc ^= bytes[i] & 255;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
            }
        }
        return crc;
    }

    /** Bounded RAM pre-roll; only the last two seconds are emitted on connection. */
    static final class LogBuffer {
        private static final long PRE_ROLL_MS = 2000L;
        private static final int MAX_CHARACTERS = 65536;
        private final ArrayDeque<Record> pending = new ArrayDeque<Record>();
        private int characters;

        synchronized void write(String line, long now) {
            while (!pending.isEmpty() && now - pending.peekFirst().time > PRE_ROLL_MS) removeFirst();
            if (CURRENT.capturing(now)) {
                while (!pending.isEmpty()) {
                    Record record = removeFirst();
                    System.out.println(record.line.replaceFirst(" t=", " preRoll=true t="));
                }
                System.out.println(line);
            } else if (line.length() <= MAX_CHARACTERS) {
                while (characters + line.length() > MAX_CHARACTERS) removeFirst();
                pending.addLast(new Record(line, now));
                characters += line.length();
            }
        }

        synchronized void clear() {
            pending.clear();
            characters = 0;
        }

        private Record removeFirst() {
            Record record = pending.removeFirst();
            characters -= record.line.length();
            return record;
        }
    }

    private static final class Record {
        final String line;
        final long time;
        Record(String line, long time) { this.line = line; this.time = time; }
    }
}
