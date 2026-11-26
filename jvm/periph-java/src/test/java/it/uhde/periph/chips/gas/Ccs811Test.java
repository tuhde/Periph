package it.uhde.periph.chips.gas;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Ccs811Test {

    /** ALG_RESULT_DATA: eCO2 500 ppm, TVOC 15 ppb, STATUS 0x98, ERROR_ID 0, RAW 0x8C88. */
    private static final int[] RESULT = {0x01, 0xF4, 0x00, 0x0F, 0x98, 0x00, 0x8C, 0x88};

    /** Each register is its own mailbox; STATUS tracks FW_MODE and DATA_READY. */
    private static class Mock extends MockConnection {
        final Map<Integer, int[]> mail = new HashMap<>();
        boolean appValid = true, fwMode = false, dataReady = true, readyAfterStart = true;
        InputPin pin;

        Mock() {
            mail.put(0x20, new int[]{0x81});
            mail.put(0x21, new int[]{0x12});
            mail.put(0x23, new int[]{0x10, 0x00});
            mail.put(0x24, new int[]{0x11, 0x02});
            mail.put(0x02, RESULT);
            mail.put(0x03, new int[]{0x8C, 0x88});
            mail.put(0x06, new int[]{0x06, 0x40, 0x03, 0x20});
            mail.put(0x11, new int[]{0x84, 0x7B});
            mail.put(0xE0, new int[]{0x00});
        }

        @Override
        public void write(byte[] data) throws IOException {
            super.write(data);
            int reg = data[0] & 0xFF;
            if (reg == 0xF4 && data.length == 1) {
                if (readyAfterStart) fwMode = true;
            } else if (reg == 0xFF) {
                fwMode = false;
            } else if (data.length >= 2) {
                int[] v = new int[data.length - 1];
                for (int i = 1; i < data.length; i++) v[i - 1] = data[i] & 0xFF;
                mail.put(reg, v);
            }
        }

        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            super.writeRead(data, n);
            int reg = data[0] & 0xFF;
            byte[] out = new byte[n];
            if (reg == 0x00) {
                out[0] = (byte) ((fwMode ? 0x80 : 0) | (appValid ? 0x10 : 0) | (dataReady ? 0x08 : 0));
                return out;
            }
            int[] v = mail.getOrDefault(reg, new int[0]);
            for (int i = 0; i < n && i < v.length; i++) out[i] = (byte) v[i];
            if (reg == 0x02) dataReady = false;
            return out;
        }

        @Override
        public InputPin intPin() {
            return pin;
        }
    }

    private static class FakePin implements InputPin {
        EdgeHandler handler;
        EdgeTrigger trigger;

        @Override public void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t; }
        @Override public void offEdge(EdgeHandler h) { if (h == handler) handler = null; }
        @Override public boolean isHigh() { return true; }
        @Override public void close() {}
    }

    private Mock c;

    private int[] last(int reg) {
        int[] v = null;
        for (byte[] w : c.writes()) {
            if (w.length >= 2 && (w[0] & 0xFF) == reg) {
                v = new int[w.length - 1];
                for (int i = 1; i < w.length; i++) v[i - 1] = w[i] & 0xFF;
            }
        }
        return v;
    }

    private boolean wrote(Mock m, int from, int... frame) {
        List<byte[]> w = m.writes();
        for (int i = from; i < w.size(); i++) {
            byte[] f = w.get(i);
            if (f.length != frame.length) continue;
            boolean eq = true;
            for (int j = 0; j < f.length; j++) eq &= (f[j] & 0xFF) == frame[j];
            if (eq) return true;
        }
        return false;
    }

    private Ccs811Full make() throws IOException {
        c = new Mock();
        Ccs811Full s = new Ccs811Full(c);
        assertTrue(c.writes().isEmpty(), "constructor touched the bus");
        s.init();
        return s;
    }

    @Test
    void initStartsApplicationAndReads() throws IOException {
        Ccs811Full s = make();
        assertTrue(wrote(c, 0, 0xF4));
        assertArrayEquals(new int[]{0x10}, last(0x01));
        assertEquals(new Ccs811Minimal.Measurement(500, 15), s.read());
        assertFalse(c.dataReady);
        c.dataReady = true;
        assertEquals(500, s.readEco2());
        c.dataReady = true;
        assertEquals(15, s.readTvoc());
        assertFalse(s.dataReady());
        c.dataReady = true;
        assertTrue(s.dataReady());
        double[] raw = s.readRaw();
        assertEquals(35, raw[0]);
        assertEquals(136 * 1.65 / 1023, raw[1], 1e-12);
    }

    @Test
    void modeAndInterruptBits() throws IOException {
        Ccs811Full s = make();
        s.enableInterrupt(Ccs811Full.SOURCE_DATA_READY);
        assertArrayEquals(new int[]{0x18}, last(0x01));
        s.setMode(2);
        assertArrayEquals(new int[]{0x28}, last(0x01));
        s.enableInterrupt(Ccs811Full.SOURCE_THRESHOLD);
        assertArrayEquals(new int[]{0x2C}, last(0x01));
        s.disableInterrupt(Ccs811Full.SOURCE_THRESHOLD);
        assertArrayEquals(new int[]{0x20}, last(0x01));
        s.enableInterrupt(Ccs811Full.SOURCE_THRESHOLD);
        s.disableInterrupt(Ccs811Full.SOURCE_DATA_READY);
        assertArrayEquals(new int[]{0x24}, last(0x01));
        s.disableInterrupt(Ccs811Full.SOURCE_THRESHOLD);
        s.setMode(4);
        assertArrayEquals(new int[]{0x40}, last(0x01));
        s.setMode(0);
        assertArrayEquals(new int[]{0x00}, last(0x01));
        assertThrows(IllegalArgumentException.class, () -> s.setMode(5));
        assertThrows(IllegalArgumentException.class, () -> s.setMode(-1));
    }

    @Test
    void environmentThresholdsBaseline() throws IOException {
        Ccs811Full s = make();
        s.setEnvironment(48.5, 22.5);
        assertArrayEquals(new int[]{0x61, 0x00, 0x5F, 0x00}, last(0x05));
        s.setEnvironment(50, 25);
        assertArrayEquals(new int[]{0x64, 0x00, 0x64, 0x00}, last(0x05));
        assertThrows(IllegalArgumentException.class, () -> s.setEnvironment(-1, 25));
        assertThrows(IllegalArgumentException.class, () -> s.setEnvironment(101, 25));
        assertThrows(IllegalArgumentException.class, () -> s.setEnvironment(50, -26));
        assertThrows(IllegalArgumentException.class, () -> s.setEnvironment(50, 103));
        s.setThresholds(1000, 2000, 50);
        assertArrayEquals(new int[]{0x03, 0xE8, 0x07, 0xD0, 0x32}, last(0x10));
        s.setThresholds();
        assertArrayEquals(new int[]{0x05, 0xDC, 0x09, 0xC4, 0x32}, last(0x10));
        assertThrows(IllegalArgumentException.class, () -> s.setThresholds(2000, 1000, 50));
        assertThrows(IllegalArgumentException.class, () -> s.setThresholds(1000, 2000, 256));
        assertEquals(0x847B, s.getBaseline());
        s.setBaseline(0x1234);
        assertArrayEquals(new int[]{0x12, 0x34}, last(0x11));
        assertEquals(0x1234, s.getBaseline());
        assertThrows(IllegalArgumentException.class, () -> s.setBaseline(0x10000));
    }

    @Test
    void ntcStatusAndVersions() throws IOException {
        Ccs811Full s = make();
        assertArrayEquals(new int[]{1600, 800}, s.readNtc());
        assertEquals(50000.0, s.ntcResistance(100000), 1e-9);
        c.mail.put(0x06, new int[]{0x00, 0x00, 0x03, 0x20});
        assertThrows(IOException.class, () -> s.ntcResistance(100000));
        c.dataReady = false;
        assertEquals(0x90, s.status());
        c.mail.put(0xE0, new int[]{0x10});
        assertEquals(0x10, s.error());
        assertArrayEquals(new int[]{0x81, 0x12}, s.hardwareVersion());
        Ccs811Full.FirmwareVersion fw = s.firmwareVersion();
        assertEquals(new Ccs811Full.Version(1, 0, 0), fw.boot());
        assertEquals(new Ccs811Full.Version(1, 1, 2), fw.app());
    }

    @Test
    void interruptAndErrors() throws IOException {
        Ccs811Full s = make();
        assertEquals(0x98, s.pollInterrupt());
        assertTrue(c.dataReady);
        FakePin pin = new FakePin();
        List<Integer> seen = new ArrayList<>();
        s.onInterrupt(seen::add, pin);
        assertEquals(EdgeTrigger.FALLING, pin.trigger);
        pin.handler.onEdge();
        assertEquals(List.of(0x98), seen);
        c.dataReady = false;
        pin.handler.onEdge();
        assertEquals(1, seen.size());
        s.offInterrupt();
        assertNull(pin.handler);

        c.dataReady = true;
        c.mail.put(0x02, new int[]{0x01, 0xF4, 0x00, 0x0F, 0x99, 0x08});
        c.mail.put(0xE0, new int[]{0x08});
        IOException e = assertThrows(IOException.class, s::read);
        assertTrue(e.getMessage().contains("0x08"));
    }

    @Test
    void softResetRestartsApplication() throws IOException {
        Ccs811Full s = make();
        int n = c.writes().size();
        s.setMode(3);
        s.softReset();
        assertTrue(wrote(c, n, 0xFF, 0x11, 0xE5, 0x72, 0x8A));
        assertTrue(wrote(c, n, 0xF4));
        assertArrayEquals(new int[]{0x10}, last(0x01));
        c.dataReady = true;
        assertEquals(500, s.read().eco2Ppm());
    }

    @Test
    void minimalAndInitErrors() throws IOException {
        Mock m = new Mock();
        m.fwMode = true;
        Ccs811Minimal mn = new Ccs811Minimal(m);
        mn.init();
        assertFalse(wrote(m, 0, 0xF4));
        assertEquals(new Ccs811Minimal.Measurement(500, 15), mn.read());

        Mock bad = new Mock();
        bad.mail.put(0x20, new int[]{0x55});
        assertThrows(IOException.class, () -> new Ccs811Minimal(bad).init());
        Mock noApp = new Mock();
        noApp.appValid = false;
        assertThrows(IOException.class, () -> new Ccs811Minimal(noApp).init());
        Mock stuck = new Mock();
        stuck.readyAfterStart = false;
        assertThrows(IOException.class, () -> new Ccs811Minimal(stuck).init());
    }
}
