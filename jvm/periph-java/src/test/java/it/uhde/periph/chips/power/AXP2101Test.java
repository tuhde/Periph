package it.uhde.periph.chips.power;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXP2101Test {

    private interface Action {
        void run(AXP2101Full f) throws IOException;
    }

    private static MockConnection connection(int... regValuePairs) {
        MockConnection c = new MockConnection();
        c.setRegister(0x30, 0x03);   // ADC_ENABLE reset
        c.setRegister(0x18, 0x0A);   // MODULE_ENABLE reset
        for (int i = 0; i < regValuePairs.length; i += 2) c.setRegister(regValuePairs[i], regValuePairs[i + 1]);
        return c;
    }

    private static int lastWriteTo(MockConnection connection, int reg) {
        var writes = connection.writes();
        for (int i = writes.size() - 1; i >= 0; i--) {
            byte[] w = writes.get(i);
            if (w.length == 2 && (w[0] & 0xFF) == reg) return w[1] & 0xFF;
        }
        return -1;
    }

    /** Preload the registers, run the action on a fresh Full driver, check the last write per register. */
    private static void expect(int[] regs, Action action, int... regValuePairs) throws IOException {
        MockConnection c = connection(regs);
        AXP2101Full f = new AXP2101Full(c);
        c.writes().clear();
        action.run(f);
        for (int i = 0; i < regValuePairs.length; i += 2) {
            assertEquals(regValuePairs[i + 1], lastWriteTo(c, regValuePairs[i]),
                    String.format("register 0x%02X", regValuePairs[i]));
        }
    }

    private static int[] r(int... v) {
        return v;
    }

    private static void rejects(Action action) throws IOException {
        AXP2101Full f = new AXP2101Full(connection(0x64, 3));
        assertThrows(IllegalArgumentException.class, () -> action.run(f));
    }

    @Test
    void minimalInitAndReads() throws IOException {
        MockConnection c = connection(0x34, 0x0F, 0x35, 0xA0, 0xA4, 87, 0x00, 0x20, 0x01, 0x22);
        AXP2101Minimal m = new AXP2101Minimal(c);
        assertEquals(0x1F, lastWriteTo(c, 0x30));
        assertEquals(0x0A, lastWriteTo(c, 0x18));
        assertEquals(4.0, m.batteryVoltage(), 1e-9);
        assertEquals(87, m.batteryPercent());
        assertEquals(AXP2101Minimal.CHARGE_CC, m.chargeState());
        assertTrue(m.vbusPresent());
        c.setRegister(0x00, 0x00);
        assertEquals(false, m.vbusPresent());
    }

    @Test
    void initPreservesGaugeBitsAndMasksDebugBits() throws IOException {
        MockConnection c = connection(0x18, 0x02, 0x34, 0xCF, 0x35, 0xA0);
        AXP2101Minimal m = new AXP2101Minimal(c);
        assertEquals(0x0A, lastWriteTo(c, 0x18));
        assertEquals(4.0, m.batteryVoltage(), 1e-9);
    }

    @Test
    void busFailureThrows() {
        MockConnection c = new MockConnection();
        c.failNextReads(0);
        MockConnection dead = new MockConnection() {
            @Override public byte[] writeRead(byte[] data, int n) throws IOException {
                throw new IOException("nack");
            }
        };
        assertThrows(IOException.class, () -> new AXP2101Minimal(dead));
    }

    @Test
    void fullMeasurementsAndStatus() throws IOException {
        MockConnection c = connection(0x34, 0x0F, 0x35, 0xA0, 0x36, 0x03, 0x37, 0xE8, 0x38, 0x13, 0x39, 0x88,
                0x3A, 0x10, 0x3B, 0x68, 0x3C, 0x1C, 0x3D, 0x6A, 0xA4, 50, 0x00, 0x3F, 0x01, 0x4B);
        AXP2101Full f = new AXP2101Full(c);
        assertEquals(5.0, f.vbusVoltage(), 1e-9);
        assertEquals(4.2, f.systemVoltage(), 1e-9);
        assertEquals(0.5, f.tsVoltage(), 1e-9);
        assertEquals(22.0, f.dieTemperature(), 1e-9);
        AXP2101Full.Readings all = f.readAll();
        assertEquals(50, all.batteryPercent());
        assertEquals(4.0, all.batteryVoltage(), 1e-9);
        AXP2101Full.Status s = f.status();
        assertTrue(s.vbusGood() && s.batfetOpen() && s.batteryPresent() && s.batteryActive()
                && s.thermalRegulation() && s.currentLimit());
        assertEquals("discharge", s.batteryDirection());
        assertTrue(!s.systemOn() && s.vindpm());
        assertEquals(3, s.chargeState());
    }

    @Test
    void getRail() throws IOException {
        AXP2101Full f = new AXP2101Full(connection(0x80, 0x0F, 0x83, 0x80 | 18));
        AXP2101Full.Rail rail = f.getRail("dcdc2");
        assertTrue(rail.enabled());
        assertEquals(0.68, rail.volts(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> f.getRail("nope"));
    }

    @Test
    void chargerSetters() throws IOException {
        expect(r(), f -> f.setAdcChannels(true, false, true, false, false, true), 0x30, 0x25);
        expect(r(0x36, 0), f -> f.setAdcLowFrequency(true), 0x36, 0x80);
        expect(r(0x18, 0x0A), f -> f.setCharging(false), 0x18, 0x08);
        expect(r(), f -> f.setChargeVoltage(4.2), 0x64, 3);
        expect(r(), f -> f.setChargeVoltage(4.4), 0x64, 5);
        expect(r(0x64, 3), f -> f.setChargeCurrent(0.5), 0x62, 11);
        expect(r(0x64, 3), f -> f.setChargeCurrent(0.1), 0x62, 4);
        expect(r(0x64, 3), f -> f.setChargeCurrent(1.4), 0x62, 20);
        expect(r(0x64, 1), f -> f.setChargeCurrent(1.5), 0x62, 21);
        expect(r(0x61, 0xF0), f -> f.setPrechargeCurrent(0.125), 0x61, 0xF5);
        expect(r(0x63, 0xE0), f -> f.setTermination(0.05, true), 0x63, 0xF2);
        expect(r(0x63, 0xE0), f -> f.setTermination(0.05, false), 0x63, 0xE2);
        expect(r(), f -> f.setInputCurrentLimit(2.0), 0x16, 5);
        expect(r(), f -> f.setInputVoltageLimit(4.36), 0x15, 6);
        expect(r(), f -> f.setMinSystemVoltage(3.7), 0x14, 5);
        expect(r(0x67, 0x08), f -> f.setChargeTimers(60, 12, true, true, true), 0x67, 0xEE);
        expect(r(0x67, 0x08), f -> f.setChargeTimers(70, 20, false, false, false), 0x67, 0x3B);
        expect(r(), f -> f.setThermalRegulation(120), 0x65, 3);
        expect(r(), f -> f.setDieProtection(135, false), 0x13, 0x04);
        expect(r(0x68, 1), f -> f.setBatteryDetection(false), 0x68, 0);
        expect(r(0x6A, 0xF8), f -> f.setBackupCharging(true, 3.3), 0x6A, 0xFF, 0x18, 0x0E);
        expect(r(0x69, 0xC0), f -> f.setChgled("manual", "blink_4hz"), 0x69, 0xE5);
        expect(r(0x69, 0xC0), f -> f.setChgled("type_b", "hiz"), 0x69, 0xC3);
        expect(r(0x69, 0xC1), f -> f.setChgled("off", "hiz"), 0x69, 0xC0);
    }

    @Test
    void temperatureSetters() throws IOException {
        expect(r(0x50, 0x20), f -> f.configureTs(false, 60, "always"), 0x50, 0x3F);
        expect(r(), f -> f.setTsLimits(1.312, 0.176, 1.984, 0.152), 0x54, 41, 0x55, 88, 0x56, 62, 0x57, 76);
        expect(r(), f -> f.setTsHysteresis(0.032, 0.004), 0x52, 2, 0x53, 1);
        expect(r(0x59, 0x80), f -> f.configureJeita(true, 0.8, 0.24, true, false, 0, 1),
                0x59, 0x94, 0x5A, 50, 0x5B, 30, 0x58, 1);
    }

    @Test
    void railSetters() throws IOException {
        expect(r(0x83, 0x80), f -> f.setRailVoltage("dcdc2", 1.5), 0x83, 0x80 | 85);
        expect(r(0x83, 0x80), f -> f.setRailVoltage("dcdc2", 1.2), 0x83, 0x80 | 70);
        expect(r(), f -> f.setRailVoltage("dcdc1", 3.3), 0x82, 18);
        expect(r(), f -> f.setRailVoltage("dcdc4", 1.84), 0x85, 102);
        expect(r(), f -> f.setRailVoltage("aldo2", 3.3), 0x93, 28);
        expect(r(), f -> f.setRailVoltage("cpusldo", 1.0), 0x98, 10);
        expect(r(), f -> f.setRailVoltage("dldo1", 3.3), 0x99, 28);
        expect(r(), f -> f.setRailVoltage("dldo2", 1.4), 0x9A, 18);
        expect(r(0x90, 0), f -> f.setRailEnabled("bldo2", true), 0x90, 0x20);
        expect(r(0x90, 0x20), f -> f.setRailEnabled("dldo1", true), 0x90, 0xA0);
        expect(r(0x90, 0xA0), f -> f.setRailEnabled("bldo2", false), 0x90, 0x80);
        expect(r(0x91, 0), f -> f.setRailEnabled("dldo2", true), 0x91, 0x01);
        expect(r(0x81, 0), f -> f.configureDcdc("dcdc3", true, true), 0x81, 0x10, 0x84, 0x80);
        expect(r(0x80, 0x0F, 0x81, 0x10), f -> f.configureDcdcGlobal(true, true, true, true, 240),
                0x80, 0x6F, 0x81, 0xD3);
    }

    @Test
    void systemSetters() throws IOException {
        expect(r(0x10, 0x30), f -> f.setOffDischarge(false), 0x10, 0x10);
        expect(r(0x10, 0x30), AXP2101Full::powerOff, 0x10, 0x31);
        expect(r(0x10, 0x30), AXP2101Full::restart, 0x10, 0x32);
        expect(r(0x26, 0x08), AXP2101Full::sleep, 0x26, 0x09);
        expect(r(0x26, 0x08), AXP2101Full::wakeup, 0x26, 0x0A);
        expect(r(0x26, 0x03), f -> f.configureSleep(true, false, true), 0x26, 0x17);
        expect(r(0x27, 0xC0, 0x22, 0x04, 0x10, 0x30), f -> f.configurePowerKey(128, 10, 2.5, true, true, true),
                0x27, 0xFC, 0x22, 0x07, 0x10, 0x34);
        expect(r(0x22, 0x03, 0x23, 0), f -> f.configurePowerOff(false, 3.3, true, 0x05),
                0x22, 0x03, 0x24, 7, 0x23, 0x25);
        expect(r(0x25, 0x20, 0x10, 0x30), f -> f.configurePwrok(true, 64, true, true, true), 0x25, 0x3F, 0x10, 0x38);
        expect(r(0x12, 0), f -> f.setBatfetOffEnable(true), 0x12, 0x08);
        Map<String, Integer> seq = new HashMap<>();
        seq.put("dcdc1", 1);
        seq.put("dcdc4", null);
        seq.put("dldo2", 2);
        expect(r(0x28, 0xFF, 0x2B, 0x0F), f -> f.configureFastPoweron(true, false, seq), 0x28, 0xFD, 0x2B, 0x8B);
        expect(r(0x19, 0, 0x18, 0x0A), f -> f.configureWatchdog(true, 128, "power_cycle"), 0x19, 0x37, 0x18, 0x0B);
        expect(r(0x19, 0x06), AXP2101Full::feedWatchdog, 0x19, 0x0E);
        expect(r(), f -> f.configureBatteryWarning(15, 5), 0x1A, 0xA5);
        expect(r(0x18, 0x02), f -> f.setGaugeEnabled(true), 0x18, 0x0A);
        expect(r(0x17, 0), f -> f.resetGauge(false), 0x17, 0x08);
        expect(r(0x17, 0), f -> f.resetGauge(true), 0x17, 0x04);
        expect(r(0x1B, 0), f -> f.setGpio1(true), 0x1B, 0x04);
        expect(r(0x1B, 0x04), f -> f.setGpio1(false), 0x1B, 0x00);
        expect(r(), f -> f.writeDataBuffer(2, 0xAB), 0x06, 0xAB);
    }

    @Test
    void invalidArgumentsAreRejected() throws IOException {
        rejects(f -> f.setChargeVoltage(4.25));
        rejects(f -> f.setChargeCurrent(1.5));
        rejects(f -> f.setChargeCurrent(-0.1));
        rejects(f -> f.setPrechargeCurrent(0.3));
        rejects(f -> f.setInputCurrentLimit(1.2));
        rejects(f -> f.configureJeita(true, null, null, true, false, 3, 0));
        rejects(f -> f.setRailVoltage("dcdc3", 1.84));
        rejects(f -> f.setRailVoltage("dldo1", 3.5));
        rejects(f -> f.setRailVoltage("foo", 1.0));
        rejects(f -> f.setRailVoltage("aldo1", 0.3));
        rejects(f -> f.configureDcdc("dcdc1", false, true));
        rejects(f -> f.configurePowerKey(100, 6, 1.5, true, false, false));
        rejects(f -> f.configureFastPoweron(true, true, Map.of("x", 1)));
        rejects(f -> f.configureWatchdog(true, 3, "irq"));
        rejects(f -> f.configureBatteryWarning(25, 1));
        rejects(f -> f.readDataBuffer(4));
    }

    @Test
    void dataBufferAndSources() throws IOException {
        MockConnection c = connection(0x20, 0x21, 0x21, 0x81);
        AXP2101Full f = new AXP2101Full(c);
        f.writeDataBuffer(2, 0xAB);
        assertEquals(0xAB, f.readDataBuffer(2));
        assertEquals(0x21, f.powerOnSource());
        assertEquals(0x81, f.powerOffSource());
    }

    @Test
    void interrupts() throws IOException {
        MockConnection c = connection(0x40, 0xFF, 0x41, 0xFC, 0x42, 0x5F);
        AXP2101Full f = new AXP2101Full(c);
        f.disableInterrupt(AXP2101Full.SOURCE_VBUS_INSERT | AXP2101Full.SOURCE_CHARGE_DONE);
        assertEquals(0x7C, lastWriteTo(c, 0x41));
        assertEquals(0x4F, lastWriteTo(c, 0x42));
        f.enableInterrupt(AXP2101Full.SOURCE_VBUS_INSERT | AXP2101Full.SOURCE_PWRON_SHORT);
        assertEquals(0xFC, lastWriteTo(c, 0x41));

        c.setRegister(0x48, 0x10, 0x88, 0x01);
        c.writes().clear();
        assertEquals(0x018810, f.pollInterrupt());
        assertEquals(0x10, lastWriteTo(c, 0x48));
        assertEquals(0x88, lastWriteTo(c, 0x49));
        assertEquals(0x01, lastWriteTo(c, 0x4A));
        c.setRegister(0x48, 0, 0, 0);
        c.writes().clear();
        assertEquals(0, f.pollInterrupt());
        assertEquals(0, c.writes().stream().filter(w -> w.length == 2).count());
    }

    @Test
    void pollingFallbackDeliversEvent() throws Exception {
        MockConnection c = connection(0x49, 0x80);
        AXP2101Full f = new AXP2101Full(c);
        java.util.concurrent.CompletableFuture<Integer> got = new java.util.concurrent.CompletableFuture<>();
        f.onInterrupt(got::complete);
        assertEquals(AXP2101Full.SOURCE_VBUS_INSERT, got.get(2, java.util.concurrent.TimeUnit.SECONDS));
        f.offInterrupt();
    }
}
