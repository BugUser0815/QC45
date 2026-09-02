package de.rothner.qc45;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class Ocpp15BridgeServerTest {
    @Test
    public void forwardsOnlyFirstEnergySampleLikeWorkingAugustVersion() throws Exception {
        String xml = "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" "
            + "xmlns:cs=\"urn://Ocpp/Cs/2012/06/\"><soap:Body><cs:meterValuesRequest>"
            + "<cs:connectorId>2</cs:connectorId><cs:transactionId>77</cs:transactionId>"
            + "<cs:values><cs:timestamp>2026-08-27T08:00:00Z</cs:timestamp>"
            + "<cs:values><cs:value>1.234</cs:value><cs:measurand>Energy.Active.Import.Register</cs:measurand><cs:unit>kWh</cs:unit></cs:values>"
            + "<cs:values><cs:value>16.2</cs:value><cs:measurand>Current.Import</cs:measurand><cs:phase>L1</cs:phase><cs:unit>A</cs:unit></cs:values>"
            + "</cs:values><cs:values><cs:timestamp>2026-08-27T08:00:10Z</cs:timestamp>"
            + "<cs:values><cs:value>10.5</cs:value><cs:measurand>Power.Active.Import</cs:measurand><cs:unit>kW</cs:unit></cs:values>"
            + "</cs:values></cs:meterValuesRequest></soap:Body></soap:Envelope>";

        assertEquals(
            "{\"connectorId\":2,\"transactionId\":77,\"meterValue\":["
            + "{\"timestamp\":\"2026-08-27T08:00:00Z\",\"sampledValue\":["
            + "{\"value\":\"1.234\",\"measurand\":\"Energy.Active.Import.Register\",\"unit\":\"kWh\"}]}]}",
            Ocpp15BridgeServer.meterValuesJson(xml));
    }

    @Test
    public void keepsLegacyMeterValuesWrapperCompatible() throws Exception {
        String xml = "<meterValues><connectorId>1</connectorId><values>"
            + "<timestamp>2026-08-27T08:00:00Z</timestamp><values>"
            + "<value>42</value><unit>Wh</unit></values></values></meterValues>";

        assertEquals(
            "{\"connectorId\":1,\"meterValue\":[{\"timestamp\":\"2026-08-27T08:00:00Z\","
            + "\"sampledValue\":[{\"value\":\"42\",\"unit\":\"Wh\"}]}]}",
            Ocpp15BridgeServer.meterValuesJson(xml));
    }

    @Test
    public void keepsBareQc45PeriodicValueAsDefaultEnergySample() throws Exception {
        String xml = "<meterValuesRequest><connectorId>1</connectorId><transactionId>91</transactionId>"
            + "<values><timestamp>2026-09-02T09:56:05Z</timestamp><values>"
            + "<value>3</value></values></values></meterValuesRequest>";

        assertEquals(
            "{\"connectorId\":1,\"transactionId\":91,\"meterValue\":[{"
            + "\"timestamp\":\"2026-09-02T09:56:05Z\",\"sampledValue\":[{"
            + "\"value\":\"3\"}]}]}",
            Ocpp15BridgeServer.meterValuesJson(xml));
    }

    @Test
    public void preservesExplicitEnergyRegisterSample() throws Exception {
        String xml = "<meterValuesRequest><connectorId>2</connectorId><values>"
            + "<timestamp>2026-09-02T09:56:05Z</timestamp><values>"
            + "<value>13790</value><measurand>Energy.Active.Import.Register</measurand>"
            + "<unit>Wh</unit></values></values></meterValuesRequest>";

        assertEquals(
            "{\"connectorId\":2,\"meterValue\":[{\"timestamp\":\"2026-09-02T09:56:05Z\","
            + "\"sampledValue\":[{\"value\":\"13790\","
            + "\"measurand\":\"Energy.Active.Import.Register\",\"unit\":\"Wh\"}]}]}",
            Ocpp15BridgeServer.meterValuesJson(xml));
    }

    @Test
    public void doesNotInventPowerMeasurandForIncompleteSample() throws Exception {
        String xml = "<meterValuesRequest><connectorId>3</connectorId><values>"
            + "<timestamp>2026-09-02T09:56:05Z</timestamp><values>"
            + "<value>27</value><unit>kW</unit></values></values></meterValuesRequest>";

        assertEquals(
            "{\"connectorId\":3,\"meterValue\":[{\"timestamp\":\"2026-09-02T09:56:05Z\","
            + "\"sampledValue\":[{\"value\":\"27\",\"unit\":\"kW\"}]}]}",
            Ocpp15BridgeServer.meterValuesJson(xml));
    }

    @Test(expected = Exception.class)
    public void externalEntitiesAreRejected() throws Exception {
        Ocpp15BridgeServer.meterValuesJson(
            "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
            + "<meterValuesRequest><connectorId>1</connectorId><values>"
            + "<timestamp>2026-08-27T08:00:00Z</timestamp><values>"
            + "<value>&e;</value></values></values></meterValuesRequest>");
    }
}
