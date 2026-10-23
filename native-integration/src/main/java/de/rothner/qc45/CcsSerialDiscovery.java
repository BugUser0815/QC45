package de.rothner.qc45;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Discovers existing master streams; never opens a port or sends a message. */
final class CcsSerialDiscovery {
    private CcsSerialDiscovery() {}

    static Reader[] readers() {
        List<Reader> result = new ArrayList<Reader>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
        Field target = field(Thread.class, "target");
        for (Thread thread : liveThreads()) {
            if (thread == null || !thread.isAlive()) continue;
            Object runnable = value(thread, target);
            Reader reader = reader(thread.getName(), thread, runnable);
            if (reader != null && !seen.containsKey(reader.inputOwner)) {
                seen.put(reader.inputOwner, Boolean.TRUE);
                result.add(reader);
            }
        }
        return result.toArray(new Reader[result.size()]);
    }

    private static Thread[] liveThreads() {
        // Discovery runs repeatedly: enumerate threads without requesting every
        // thread's stack trace on the old station JVM.
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) root = root.getParent();
        int size = Math.max(32, root.activeCount() + 16);
        while (true) {
            Thread[] threads = new Thread[size];
            int count = root.enumerate(threads, true);
            if (count < size) return threads;
            size *= 2;
        }
    }

    static Reader reader(String threadName, Object thread, Object runnable) {
        // Some EVCSD versions use a Runnable, others a Thread subclass.
        Object inputOwner = serialReader(runnable) ? runnable : serialReader(thread) ? thread : null;
        if (inputOwner == null) return null;
        Object owner = value(inputOwner, "owner");
        if (owner == null) {
            // ECJ can name the enclosing-instance field this$1 (or higher).
            for (Class<?> type = inputOwner.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getName().startsWith("this$")) {
                        Object enclosing = value(inputOwner, field);
                        if (master(enclosing)) owner = enclosing;
                    }
                }
            }
        }
        return new Reader(inputOwner, owner, threadName);
    }

    static Selection select(Object central, Reader[] readers) {
        Object configured = value(central, "communications");
        if (master(configured)) {
            Reader exact = matchingReader(configured, readers);
            if (exact != null) return new Selection(configured, exact, "configured-master");
        }

        // A configured but unopened ttyS0 object can coexist with the actual
        // USB master. Follow a live reader's owner only if its serializer proves
        // it is the master; never attach to an arbitrary USB/AC reader.
        Object active = null;
        for (Reader reader : readers) {
            if (!master(reader.channel)) continue;
            if (active != null && active != reader.channel) {
                return new Selection(null, null, "ambiguous-live-masters");
            }
            active = reader.channel;
        }
        if (active != null) {
            return new Selection(active, matchingReader(active, readers), "live-reader-owner");
        }
        return new Selection(master(configured) ? configured : null, null,
            "waiting-for-master-reader");
    }

    private static Reader matchingReader(Object channel, Reader[] readers) {
        Reader byOwner = null;
        Reader byName = null;
        boolean ownerAmbiguous = false;
        boolean nameAmbiguous = false;
        String port = port(channel);
        for (Reader reader : readers) {
            if (reader.channel == channel) {
                if (byOwner != null && byOwner.inputOwner != reader.inputOwner) ownerAmbiguous = true;
                byOwner = reader;
            } else if (reader.channel == null && portMatches(reader.threadName, port)) {
                if (byName != null && byName.inputOwner != reader.inputOwner) nameAmbiguous = true;
                byName = reader;
            }
        }
        if (byOwner != null) return ownerAmbiguous ? null : byOwner;
        return nameAmbiguous ? null : byName;
    }

    private static boolean portMatches(String name, String port) {
        if (name == null || "unknown".equals(port)) return false;
        int pos = name.indexOf(port);
        if (pos < 0) return false;
        int end = pos + port.length();
        return end == name.length() || !Character.isLetterOrDigit(name.charAt(end));
    }

    private static boolean serialReader(Object object) {
        if (object == null) return false;
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().indexOf("SerialReader") >= 0) {
                return field(object.getClass(), "in") != null;
            }
        }
        return false;
    }

    private static boolean master(Object channel) {
        Object serializer = value(channel, "pSerializer");
        return serializer != null
            && serializer.getClass().getName().indexOf("MasterProtoSerializer") >= 0;
    }

    static String port(Object channel) {
        Object value = value(channel, "portName");
        return value == null || String.valueOf(value).trim().length() == 0
            ? "unknown" : String.valueOf(value);
    }

    static String serializer(Object channel) {
        Object value = value(channel, "pSerializer");
        return value == null ? "null" : value.getClass().getName();
    }

    static Object value(Object owner, String name) {
        return owner == null ? null : value(owner, field(owner.getClass(), name));
    }

    private static Object value(Object owner, Field field) {
        if (owner == null || field == null) return null;
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (Exception ignored) { return null; }
    }

    static Field field(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    static final class Reader {
        final Object inputOwner;
        final Object channel;
        final String threadName;

        Reader(Object inputOwner, Object channel, String threadName) {
            this.inputOwner = inputOwner;
            this.channel = channel;
            this.threadName = threadName;
        }
    }

    static final class Selection {
        final Object channel;
        final Reader reader;
        final String reason;

        Selection(Object channel, Reader reader, String reason) {
            this.channel = channel;
            this.reader = reader;
            this.reason = reason;
        }
    }
}
