package pt.efacec.es.evcsd.ui;

/** Immutable decoder for the native integration's versioned AC/DC UI block. */
final class LoadBalancingTelemetry {
    static final int FIRST_REGISTER = 126;
    static final int REGISTER_COUNT = 20;
    static final int VERSION = 1;

    static final int FLAG_DC_SESSION = 1 << 0;
    static final int FLAG_AC_SESSION = 1 << 1;
    static final int FLAG_DC_FLOW = 1 << 2;
    static final int FLAG_AC_FLOW = 1 << 3;
    static final int FLAG_BLOCKED = 1 << 4;
    static final int FLAG_FAILBACK = 1 << 5;
    static final int FLAG_LOAD_METER = 1 << 6;
    static final int FLAG_STARTUP = 1 << 7;
    static final int FLAG_SHUTDOWN = 1 << 8;
    static final int FLAG_DEMAND_TRANSFER = 1 << 9;
    static final int FLAG_STAGE_LIMIT = 1 << 10;
    static final int FLAG_CONFIGURATION = 1 << 11;
    static final int FLAG_LIMIT_MISMATCH = 1 << 12;

    final int flags;
    final int activeDcConnector;
    final int dcActualKw;
    final int dcRequestedKw;
    final int dcGridKw;
    final int dcStageCapKw;
    final int dcEffectiveKw;
    final int dcSocPct;
    final int dcSeconds;
    final long dcEnergyWh;
    final int acActualKw;
    final int acRequestedKw;
    final int acGridKw;
    final int acStageCapKw;
    final int acEffectiveKw;
    final int acSeconds;
    final long acEnergyWh;

    private LoadBalancingTelemetry(int[] value) {
        flags = value[1];
        activeDcConnector = value[2];
        dcActualKw = value[3];
        dcRequestedKw = value[4];
        dcGridKw = value[5];
        dcStageCapKw = value[6];
        dcEffectiveKw = value[7];
        dcSocPct = value[8];
        dcSeconds = value[9];
        dcEnergyWh = words(value[10], value[11]);
        acActualKw = value[12];
        acRequestedKw = value[13];
        acGridKw = value[14];
        acStageCapKw = value[15];
        acEffectiveKw = value[16];
        acSeconds = value[17];
        acEnergyWh = words(value[18], value[19]);
    }

    static LoadBalancingTelemetry decode(int[] value) {
        if (value == null || value.length != REGISTER_COUNT || value[0] != VERSION) {
            throw new IllegalArgumentException("unsupported AC/DC UI telemetry block");
        }
        if (value[2] < 0 || value[2] > 2) {
            throw new IllegalArgumentException("invalid active DC connector");
        }
        return new LoadBalancingTelemetry(value);
    }

    boolean has(int flag) {
        return (flags & flag) != 0;
    }

    boolean dcSession() { return has(FLAG_DC_SESSION); }
    boolean acSession() { return has(FLAG_AC_SESSION); }
    boolean blocked() { return has(FLAG_BLOCKED); }
    boolean demandTransfer() { return has(FLAG_DEMAND_TRANSFER); }
    int totalActualKw() { return dcActualKw + acActualKw; }
    int totalEffectiveKw() { return dcEffectiveKw + acEffectiveKw; }
    long totalEnergyWh() { return dcEnergyWh + acEnergyWh; }

    private static long words(int high, int low) {
        return (((long)high & 0xffffL) << 16) | ((long)low & 0xffffL);
    }
}
