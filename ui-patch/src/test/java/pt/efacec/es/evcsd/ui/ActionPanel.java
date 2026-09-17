package pt.efacec.es.evcsd.ui;

/** Compile-time stand-in for the proprietary EVCSD interface. */
public interface ActionPanel<T> {
    void start();
    void stop();
    void setInfo(T info);
}
