package de.rothner.qc45;

/** Shared read boundary for the normal controller and independent protection. */
interface GridCurrentIo {
    KsemClient.Currents readCurrents() throws Exception;
}
