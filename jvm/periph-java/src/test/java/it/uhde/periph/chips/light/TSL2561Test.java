package it.uhde.periph.chips.light;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TSL2561Test {

    private static final double[][] T = {{0.50, 0.0304, 0.062}, {0.61, 0.0224, 0.031}, {0.80, 0.0128, 0.0153}, {1.30, 0.00146, 0.00112}};
    private static final double[][] CS = {{0.52, 0.0315, 0.0593}, {0.65, 0.0229, 0.0291}, {0.80, 0.0157, 0.0180}, {1.30, 0.00338, 0.00260}};

    @BeforeAll
    static void noWait() {
        TSL2561Minimal.waitPercent = 0;   // skip the integration waits
    }

    static double ref(double ch0, double ch1, double gain, double scale, double[][] k) {
        double c0 = ch0 * (16 / gain) / scale, c1 = ch1 * (16 / gain) / scale, r = c1 / c0;
        if (r <= k[0][0]) return k[0][1] * c0 - k[0][2] * c0 * Math.pow(r, 1.4);
        for (int i = 1; i < 4; i++) if (r <= k[i][0]) return k[i][1] * c0 - k[i][2] * c1;
        return 0;
    }

    static void counts(MockConnection c, int ch0, int ch1) {
        c.setRegister(0x9C, ch0 & 0xFF, ch0 >> 8, ch1 & 0xFF, ch1 >> 8);
    }

    static MockConnection mock() {
        MockConnection c = new MockConnection();
        c.setRegister(0x8A, 0x15);
        return c;
    }

    static int reg(MockConnection c, int r) { return c.registers().getOrDefault(r, 0); }

    static boolean wrote(MockConnection c, int... bytes) {
        byte[] want = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) want[i] = (byte) bytes[i];
        for (byte[] w : c.writes()) if (Arrays.equals(w, want)) return true;
        return false;
    }

    /** Counts follow the TIMING register: the light level is fixed, counts scale with gain and time. */
    static class RangeMock extends MockConnection {
        final double base;
        RangeMock(double base) { this.base = base; }

        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            if ((data[0] & 0xFF) == 0x9C) {
                int t = registers().getOrDefault(0x81, 0x02);
                double gain = (t & 0x10) != 0 ? 16 : 1;
                double scale = new double[]{0.034, 0.252, 1.0}[t & 3];
                double limit = new double[]{5047, 37177, 65535}[t & 3];
                counts(this, (int) Math.min(limit, Math.floor(base * gain * scale)), (int) Math.min(limit, Math.floor(base * 0.2 * gain * scale)));
            }
            return super.writeRead(data, n);
        }
    }

    static class FakePin implements InputPin {
        EdgeHandler handler;
        @Override public void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; }
        @Override public void offEdge(EdgeHandler h) { if (handler == h) handler = null; }
        @Override public boolean isHigh() { return false; }
        @Override public void close() {}
    }

    @Test
    void initSequence() throws Exception {
        MockConnection c = mock();
        new TSL2561Full(c);
        List<byte[]> data = new ArrayList<>();
        for (byte[] w : c.writes()) if (w.length > 1) data.add(w);
        assertEquals(2, data.size());
        assertArrayEquals(new byte[]{(byte) 0x80, 0x03}, data.get(0));
        assertArrayEquals(new byte[]{(byte) 0x81, 0x02}, data.get(1));
        assertTrue(wrote(c, 0x80), "CONTROL read back");
    }

    @Test
    void initRejectsBadControl() {
        MockConnection dead = new MockConnection() {
            @Override
            public void write(byte[] data) throws IOException {
                super.write(data);
                setRegister(0x80, 0x00);
            }
        };
        assertThrows(IOException.class, () -> new TSL2561Minimal(dead));
    }

    @Test
    void luxFormula() throws Exception {
        MockConnection c = mock();
        TSL2561Full chip = new TSL2561Full(c);
        counts(c, 1000, 200);
        double lx = chip.readLux();
        assertEquals(ref(1000, 200, 1, 1, T), lx, 1e-9);
        assertEquals(382.2, lx, 0.5);
        for (int c1 : new int[]{550, 700, 1000}) {
            counts(c, 1000, c1);
            assertEquals(ref(1000, c1, 1, 1, T), chip.readLux(), 1e-9);
        }
        counts(c, 1000, 1400);
        assertEquals(0.0, chip.readLux(), 0.0);
        counts(c, 0, 0);
        assertEquals(0.0, chip.readLux(), 0.0);
        assertEquals(ref(500, 100, 16, 0.252, T), TSL2561Minimal.calculateLux(500, 100, 16, 101, "T"), 1e-9);
        assertEquals(ref(500, 300, 1, 0.034, T), TSL2561Minimal.calculateLux(500, 300, 1, 14, "FN"), 1e-9);
        for (int c1 : new int[]{100, 600, 700, 1000}) {
            assertEquals(ref(1000, c1, 1, 1, CS), TSL2561Minimal.calculateLux(1000, c1, 1, 402, "CS"), 1e-9);
        }
    }

    @Test
    void rawAndSaturation() throws Exception {
        MockConnection c = mock();
        TSL2561Full chip = new TSL2561Full(c);
        counts(c, 0x1234, 0x0567);
        assertArrayEquals(new int[]{0x1234, 0x0567}, chip.readRaw());
        assertArrayEquals(new byte[]{(byte) 0x9C}, c.writes().get(c.writes().size() - 1));
        TSL2561Full.Reading r = chip.readLuxRaw();
        assertEquals(0x1234, r.ch0);
        assertEquals(ref(0x1234, 0x0567, 1, 1, T), r.lux, 1e-9);
        counts(c, 65535, 100);
        TSL2561Minimal.SaturatedException e = assertThrows(TSL2561Minimal.SaturatedException.class, chip::readLux);
        assertEquals(65535, e.ch0);
        chip.setIntegrationTime(101);
        counts(c, 37177, 10);
        assertThrows(TSL2561Minimal.SaturatedException.class, chip::readLux);
        counts(c, 37176, 10);
        assertTrue(chip.readLux() > 0);
    }

    @Test
    void configuration() throws Exception {
        MockConnection c = mock();
        TSL2561Full chip = new TSL2561Full(c);
        chip.setGain(16);
        assertEquals(0x12, reg(c, 0x81));
        assertEquals(16, chip.getGain());
        chip.setIntegrationTime(14);
        assertEquals(0x10, reg(c, 0x81));
        assertEquals(14, chip.getIntegrationTime());
        chip.setGain(1);
        chip.setIntegrationTime(101);
        assertEquals(0x01, reg(c, 0x81));
        assertThrows(IllegalArgumentException.class, () -> chip.setGain(4));
        assertThrows(IllegalArgumentException.class, () -> chip.setIntegrationTime(50));
        assertThrows(IllegalArgumentException.class, () -> chip.setPackage("X"));
        chip.setIntegrationTime(402);
        counts(c, 1000, 100);
        chip.setPackage("CS");
        assertEquals(ref(1000, 100, 1, 1, CS), chip.readLux(), 1e-9);
        chip.setGain(16);
        chip.startManual();
        assertEquals(0x1B, reg(c, 0x81));
        chip.stopManual();
        assertEquals(0x13, reg(c, 0x81));
        assertEquals(0, chip.getIntegrationTime());
    }

    @Test
    void autoRange() throws Exception {
        TSL2561Full dim = new TSL2561Full(new RangeMock(20));
        assertTrue(dim.autoRange() > 0);
        assertEquals(16, dim.getGain());
        assertEquals(402, dim.getIntegrationTime());
        TSL2561Full bright = new TSL2561Full(new RangeMock(148000));
        assertTrue(bright.autoRange() > 0);
        assertEquals(1, bright.getGain());
        assertEquals(14, bright.getIntegrationTime());
        TSL2561Full mid = new TSL2561Full(new RangeMock(5000));
        double lx = mid.autoRange();
        assertEquals(402, mid.getIntegrationTime());
        assertEquals(TSL2561Minimal.calculateLux(5000, 1000, 1, 402, "T"), lx, 1e-9);
    }

    @Test
    void powerThresholdsInterrupt() throws Exception {
        MockConnection c = mock();
        TSL2561Full chip = new TSL2561Full(c);
        assertTrue(chip.isPowered());
        chip.powerDown();
        assertFalse(chip.isPowered());
        chip.powerUp();
        assertEquals(0x03, reg(c, 0x80));
        chip.setThresholds(0x0102, 0x0A0B);
        assertTrue(wrote(c, 0xA2, 0x02, 0x01) && wrote(c, 0xA4, 0x0B, 0x0A));
        assertArrayEquals(new int[]{0x0102, 0x0A0B}, chip.getThresholds());
        assertThrows(IllegalArgumentException.class, () -> chip.setThresholds(-1, 70000));
        chip.configureInterrupt(1, 2);
        assertEquals(0x12, reg(c, 0x86));
        assertThrows(IllegalArgumentException.class, () -> chip.configureInterrupt(3, 0));
        assertThrows(IllegalArgumentException.class, () -> chip.configureInterrupt(1, 16));
        assertEquals(1, chip.readId() >> 4);
        chip.setThresholds(100, 1000);
        counts(c, 500, 0);
        assertFalse(chip.pollInterrupt());
        counts(c, 100, 0);
        assertTrue(chip.pollInterrupt());
        counts(c, 1001, 0);
        assertTrue(chip.pollInterrupt());
        chip.clearInterrupt();
        assertArrayEquals(new byte[]{(byte) 0xC0}, c.writes().get(c.writes().size() - 1));

        FakePin pin = new FakePin();
        List<Integer> seen = new ArrayList<>();
        chip.onInterrupt(seen::add, pin);
        counts(c, 4242, 0);
        pin.handler.onEdge();
        assertEquals(List.of(4242), seen);
        assertArrayEquals(new byte[]{(byte) 0xC0}, c.writes().get(c.writes().size() - 1));
        chip.offInterrupt();
        assertNull(pin.handler);
    }
}
