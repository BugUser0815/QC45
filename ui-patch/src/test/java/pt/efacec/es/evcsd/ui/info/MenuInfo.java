package pt.efacec.es.evcsd.ui.info;

public class MenuInfo {
    private final int command;
    public MenuInfo() { this(1); }
    public MenuInfo(int command) { this.command = command; }
    public int getCommand() { return command; }
}
