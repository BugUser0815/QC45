package pt.efacec.es.evcsd.ui.info;

public class EpoInfo {
    private final boolean pressed;
    public EpoInfo() { this(false); }
    public EpoInfo(boolean pressed) { this.pressed = pressed; }
    public boolean isShowEpoPressed() { return pressed; }
}
