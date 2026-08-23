package pt.efacec.es.evcsd.ui;

/** Remembers whether the shared wait screen represents an idle or active session. */
final class AlpitronicSessionState {
    static final int UNKNOWN = 0;
    static final int IDLE = -1;
    static final int CHARGING = 1;

    private static volatile int state = UNKNOWN;

    private AlpitronicSessionState() {}

    static void markIdle() {
        state = IDLE;
    }

    static void markCharging() {
        state = CHARGING;
    }

    static int get() {
        return state;
    }
}
