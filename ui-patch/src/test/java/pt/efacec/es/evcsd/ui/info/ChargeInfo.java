package pt.efacec.es.evcsd.ui.info;

/** Minimal test contract matching the methods used by the patch. */
public class ChargeInfo {
    private static volatile ChargeInfo latest;
    private final boolean charging;

    public ChargeInfo() { this(false); }
    public ChargeInfo(boolean charging) { this.charging = charging; latest = this; }
    public static ChargeInfo getLatest() { return latest; }
    public boolean isCharging() { return charging; }
}
