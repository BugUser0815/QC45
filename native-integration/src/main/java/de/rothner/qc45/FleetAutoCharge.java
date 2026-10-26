package de.rothner.qc45;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Connector-2 AutoCharge policy. All authorizations are fresh backend decisions. */
final class FleetAutoCharge {
    interface Backend { String authorize(String idTag) throws Exception; }
    interface Station {
        boolean available() throws Exception;
        void start(String idTag) throws Exception;
        /** Stop only this integration's remote session carrying exactly this idTag. */
        void stopIfOwned(String idTag) throws Exception;
    }
    interface Clock { long monotonicMillis(); long wallMillis(); }
    private final Backend backend;
    private final Station station;
    private final Clock clock;
    private final Map<String,String> vehicles;
    private final long observationTimeoutMs, transactionTimeoutMs;
    private final Set<String> attempted = new LinkedHashSet<String>();
    private Observation current;
    private Owned owned;
    private boolean running = true;

    FleetAutoCharge(Backend backend, Station station, Clock clock, Properties vehicles,
                    long observationTimeoutMs, long transactionTimeoutMs) {
        if (backend == null || station == null || clock == null || vehicles == null
                || observationTimeoutMs < 1000 || observationTimeoutMs > 5000
                || transactionTimeoutMs < 1000 || transactionTimeoutMs > 30000) {
            throw new IllegalArgumentException("invalid AutoCharge policy");
        }
        this.backend = backend;
        this.station = station;
        this.clock = clock;
        this.vehicles = vehicleMap(vehicles);
        this.observationTimeoutMs = observationTimeoutMs;
        this.transactionTimeoutMs = transactionTimeoutMs;
    }

    static Map<String,String> vehicleMap(Properties properties) {
        Map<String,String> result = new HashMap<String,String>();
        Set<String> tags = new LinkedHashSet<String>();
        for (String key : properties.stringPropertyNames()) {
            String id = evccId(key);
            String tag = properties.getProperty(key).trim();
            if (!tag.matches("[A-Za-z0-9_-]{1,20}") || result.containsKey(id) || !tags.add(tag)) {
                throw new IllegalArgumentException("AutoCharge requires unique EVCCIDs and per-vehicle OCPP idTags (1..20 ASCII characters)");
            }
            result.put(id, tag);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("AutoCharge fleet is empty");
        return Collections.unmodifiableMap(result);
    }

    static String evccId(String input) {
        String id = input.toUpperCase(java.util.Locale.ROOT);
        if (!id.matches("[0-9A-F]{12}") || "000000000000".equals(id)
                || "FFFFFFFFFFFF".equals(id) || (Integer.parseInt(id.substring(0, 2), 16) & 1) != 0) {
            throw new IllegalArgumentException("invalid six-byte unicast EVCCID");
        }
        return id;
    }

    synchronized void observe(Object source, String token, String evccId) {
        if (!running || source == null || token == null || !token.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("invalid AutoCharge observation");
        }
        String id = evccId(evccId);
        if (current != null && current.source == source && current.token.equals(token)) {
            if (!current.evccId.equals(id)) { current = null; throw new IllegalArgumentException("identity changed inside TCP connection"); }
            current.updated = clock.monotonicMillis();
            return;
        }
        current = new Observation(source, token, id, clock.monotonicMillis());
    }

    synchronized void end(Object source, String token) {
        if (current != null && current.source == source && (token == null || current.token.equals(token))) current = null;
    }

    /** One call is made by the authorization worker, never the socket receiver. */
    void authorizeCurrent() throws Exception {
        Observation candidate;
        String tag;
        synchronized (this) {
            candidate = current;
            if (!fresh(candidate) || owned != null || attempted.contains(candidate.token)) return;
            tag = vehicles.get(candidate.evccId);
            if (tag == null) { rememberAttempt(candidate.token); log("vehicle outside configured fleet; normal authorization remains available"); return; }
        }
        if (!station.available()) return;
        synchronized (this) {
            if (!fresh(candidate) || current != candidate || attempted.contains(candidate.token)) return;
            rememberAttempt(candidate.token);
        }
        String answer = backend.authorize(tag);
        if (!OcppAuthorization.accepted(answer, clock.wallMillis())) { log("backend authorization rejected; no automatic start"); return; }
        Owned start;
        synchronized (this) {
            if (!fresh(candidate) || current != candidate || owned != null) return;
            start = new Owned(tag, candidate, clock.monotonicMillis() + transactionTimeoutMs);
            owned = start;
        }
        try {
            if (!station.available()) { synchronized (this) { if (owned == start) owned = null; } return; }
            synchronized (this) { if (current != candidate || !fresh(candidate)) { if (owned == start) owned = null; return; } }
            station.start(tag);
            synchronized (this) {
                start.started = true;
                if (!running || current != candidate || !fresh(candidate)) start.abort = true;
            }
            log("backend accepted; native CCS start requested, waiting for backend transaction");
        } catch (Exception e) {
            // A native start can fail after scheduling work: keep the ownership
            // marker and stop only the matching native remote session.
            synchronized (this) { start.started = true; start.abort = true; }
            throw e;
        }
    }

    synchronized void transactionStarted(int connector, String idTag, int transactionId, boolean accepted) {
        if (connector != 2 || owned == null || !owned.idTag.equals(idTag)) return;
        if (!accepted || transactionId < 0 || current != owned.observation || !fresh(owned.observation)) {
            owned.abort = true;
            log("backend transaction rejected or vehicle observation ended; stopping owned CCS session");
        }
        else { owned.confirmed = true; owned.transactionId = transactionId; log("backend transaction confirmed tx=" + transactionId); }
    }

    synchronized void transactionEnded(int transactionId) {
        if (owned != null && owned.confirmed && owned.transactionId == transactionId) owned = null;
    }

    void watchdog() throws Exception {
        Owned check;
        synchronized (this) {
            check = owned;
            if (check == null || !check.started) return;
            if (!check.confirmed && (clock.monotonicMillis() >= check.deadline
                    || current != check.observation || !fresh(check.observation))) check.abort = true;
            if (!check.abort) {
                if (clock.monotonicMillis() >= check.deadline && station.available()) owned = null;
                return;
            }
            long now = clock.monotonicMillis();
            if (check.stopAttempted && now - check.stopAt < 500L) return;
            check.stopAttempted = true;
            check.stopAt = now;
        }
        station.stopIfOwned(check.idTag);
        // Retain the marker: retry if EVCSD has only scheduled the stop or has
        // not yet exposed the newly started session. A new session requires a
        // different PLC flow and an idle connector before it can be started.
        synchronized (this) { if (owned == check && station.available()) owned = null; }
    }

    synchronized void shutdown() {
        running = false;
        current = null;
        if (owned != null && !owned.confirmed) owned.abort = true;
    }

    private void rememberAttempt(String token) {
        attempted.add(token);
        if (attempted.size() > 256) attempted.remove(attempted.iterator().next());
    }

    private boolean fresh(Observation observation) {
        return running && observation != null && clock.monotonicMillis() - observation.updated <= observationTimeoutMs;
    }
    private static void log(String message) { System.out.println("[QC45] AutoCharge " + message); }
    private static final class Observation {
        final Object source;
        final String token, evccId;
        long updated;
        Observation(Object source, String token, String evccId, long updated) {
            this.source = source; this.token = token; this.evccId = evccId; this.updated = updated;
        }
    }
    private static final class Owned {
        final String idTag;
        final long deadline;
        final Observation observation;
        boolean started, confirmed, abort, stopAttempted;
        long stopAt;
        int transactionId = -1;
        Owned(String idTag, Observation observation, long deadline) {
            this.idTag = idTag; this.observation = observation; this.deadline = deadline;
        }
    }
}
