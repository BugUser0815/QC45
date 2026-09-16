package de.rothner.qc45;

/** Lock-free mirror: reflection code must never call back into the coordinator. */
interface ChargingSafetyIo {
    void setHardStopRequired(boolean required);
}
