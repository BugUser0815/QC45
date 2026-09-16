package pt.efacec.es.mobie.agent.statemachines;

public class CentralModule {
    public static final CentralModule INSTANCE = new CentralModule();
    public final Satellite satellite = new Satellite();
    public static CentralModule getCurrentModule() { return INSTANCE; }
    public Object[] getSatellites() { return new Object[] {satellite}; }
    public boolean isLoggedIn() { return false; }
    public static class Satellite {
        public Object transaction;
        public int power;
        public int limit;
        public boolean authorized;
        public int getSatelliteId() { return 2; }
        public Object getActiveTransaction() { return transaction; }
        public String getUser() { return "cached-rfid"; }
        public int getCurrentPower() { return power; }
        public boolean isCCSCharge() { return true; }
        public void setMaxPower(int kw) { limit = kw; }
        public int getMaxPower() { return limit; }
        public void sendCcsStart(boolean value) { authorized = value; }
    }
}
