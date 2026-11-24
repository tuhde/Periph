package it.uhde.periph.chips.light;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Si1132Test {

    /** Models the Si1132 sequencer: RESPONSE counter, parameter RAM, forced measurement. */
    static class ChipMock extends MockConnection {
        final Map<Integer, Integer> params = new HashMap<>();
        final List<Integer> cmds = new ArrayList<>();
        int vis = 1234, ir = 567, aux = 250;
        boolean stuck = false;
        int cmdError = -1;
        InputPin pin = null;

        ChipMock() { this(0x08, 0x32); }

        ChipMock(int seqId, int partId) {
            registers().put(0x00, partId);
            registers().put(0x02, seqId);
        }

        int r(int a) { return registers().getOrDefault(a, 0); }

        void count() { registers().put(0x20, (r(0x20) + 1) & 0x0F); }

        @Override
        public void write(byte[] data) throws IOException {
            super.write(data);
            if (data.length != 2) return;
            int reg = data[0] & 0xFF, val = data[1] & 0xFF;
            if (reg == 0x21) {
                registers().put(0x21, r(0x21) & ~val & 0xFF);
            } else if (reg == 0x18) {
                cmds.add(val);
                command(val);
            }
        }

        void command(int cmd) {
            if (cmdError >= 0 && cmd != 0x00) { registers().put(0x20, cmdError); return; }
            if ((cmd & 0xE0) == 0xA0) {
                params.put(cmd & 0x1F, r(0x17));
                registers().put(0x2E, params.get(cmd & 0x1F));
                count();
            } else if ((cmd & 0xE0) == 0x80) {
                registers().put(0x2E, params.getOrDefault(cmd & 0x1F, 0));
                count();
            } else if (cmd == 0x00) {
                registers().put(0x20, 0);
            } else if (cmd == 0x06) {
                if (!stuck) measure();
                count();
            } else if (cmd == 0x12) {
                for (int i = 0; i < 12; i++) registers().put(0x22 + i, 0x10 + i);
                count();
            } else if (cmd != 0x01) {
                count();
            }
        }

        void measure() {
            int a = aux;
            if (params.getOrDefault(0x01, 0) == 0x40) a = params.getOrDefault(0x0F, 0) == 0x65 ? 0x4000 : 0x2000;
            setRegister(0x22, vis & 0xFF, vis >> 8, ir & 0xFF, ir >> 8);
            setRegister(0x2C, a & 0xFF, a >> 8);
            registers().put(0x21, r(0x21) | 0x01);
        }

        @Override
        public InputPin intPin() { return pin; }
    }

    static class FakePin implements InputPin {
        EdgeHandler handler;
        EdgeTrigger trigger;
        @Override public void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t; }
        @Override public void offEdge(EdgeHandler h) { if (handler == h) handler = null; }
        @Override public boolean isHigh() { return false; }
        @Override public void close() {}
    }

    private static boolean wrote(ChipMock c, int... bytes) {
        for (byte[] w : c.writes()) {
            if (w.length != bytes.length) continue;
            boolean eq = true;
            for (int i = 0; i < w.length; i++) if ((w[i] & 0xFF) != bytes[i]) eq = false;
            if (eq) return true;
        }
        return false;
    }

    @Test
    void initConfiguresChip() throws IOException {
        ChipMock c = new ChipMock();
        new Si1132Minimal(c);
        assertTrue(wrote(c, 0x07, 0x17));
        assertTrue(wrote(c, 0x13, 0x7B, 0x6B, 0x01, 0x00));
        assertEquals(0xB0, c.params.get(0x01));
        assertEquals(0x01, c.r(0x04));
    }

    @Test
    void wrongPartIdThrows() {
        assertThrows(IOException.class, () -> new Si1132Minimal(new ChipMock(0x08, 0x45)));
    }

    @Test
    void readReturnsUvAndCounts() throws IOException {
        ChipMock c = new ChipMock();
        Si1132Minimal s = new Si1132Minimal(c);
        double[] m = s.read();
        assertEquals(2.5, m[0], 1e-6);
        assertEquals(1234, m[1], 0);
        assertEquals(567, m[2], 0);
        assertTrue(c.cmds.contains(0x06));
        assertEquals(0, c.r(0x21));
    }

    @Test
    void measurementTimeout() throws IOException {
        ChipMock c = new ChipMock();
        c.stuck = true;
        Si1132Minimal s = new Si1132Minimal(c);
        assertThrows(IOException.class, s::read);
    }

    @Test
    void commandErrorSendsNop() throws IOException {
        ChipMock c = new ChipMock();
        Si1132Full f = new Si1132Full(c);
        c.cmdError = 0x80;
        assertThrows(IOException.class, () -> f.setGain(1, 1));
        assertEquals(0x00, c.cmds.get(c.cmds.size() - 1));
    }

    @Test
    void singleChannels() throws IOException {
        Si1132Full f = new Si1132Full(new ChipMock());
        assertEquals(2.5, f.readUvIndex(), 1e-6);
        assertEquals(1234, f.readVisible());
        assertEquals(567, f.readInfrared());
        assertArrayEquals(new int[]{1234, 567, 250}, f.readRaw());
    }

    @Test
    void auxiliaryChannels() throws IOException {
        ChipMock c = new ChipMock();
        Si1132Full f = new Si1132Full(c);
        assertEquals(0x4000, f.readTemperatureRaw());
        assertEquals(0x65, c.params.get(0x0F));
        assertEquals(0xB0, c.params.get(0x01));
        assertEquals(0x2000, f.readVddRaw());
        assertEquals(0x75, c.params.get(0x0F));
    }

    @Test
    void setChannels() throws IOException {
        ChipMock c = new ChipMock();
        Si1132Full f = new Si1132Full(c);
        f.setChannels(true, false, true, false);
        assertEquals(0xA0, c.params.get(0x01));
        f.setChannels(false, true, false, true);
        assertEquals(0x50, c.params.get(0x01));
        assertThrows(IllegalArgumentException.class, () -> f.setChannels(false, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> f.setChannels(true, true, true, true));
    }

    @Test
    void gainRangeAlignmentCoefficients() throws IOException {
        ChipMock c = new ChipMock();
        Si1132Full f = new Si1132Full(c);
        f.setGain(3, 5);
        assertEquals(3, c.params.get(0x11));
        assertEquals(0x40, c.params.get(0x10));
        assertEquals(5, c.params.get(0x1E));
        assertEquals(0x20, c.params.get(0x1D));
        assertThrows(IllegalArgumentException.class, () -> f.setGain(8, 0));
        f.setHighRange(true, false);
        assertEquals(0x20, c.params.get(0x12));
        assertEquals(0x00, c.params.get(0x1F));
        f.setAlignment(true, true);
        assertEquals(0x30, c.params.get(0x06));
        f.setUvCoefficients(new int[]{1, 2, 3, 4});
        assertTrue(wrote(c, 0x13, 1, 2, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> f.setUvCoefficients(new int[]{1, 2}));
    }

    @Test
    void calibration() throws IOException {
        int[] cal = new Si1132Full(new ChipMock()).readCalibration();
        assertEquals(12, cal.length);
        assertEquals(0x10, cal[0]);
        assertEquals(0x1B, cal[11]);
    }

    @Test
    void autonomous() throws IOException {
        ChipMock c = new ChipMock();
        Si1132Full f = new Si1132Full(c);
        f.startAutonomous(0.5);
        assertEquals(0x80, c.r(0x08));
        assertEquals(0x3E, c.r(0x09));
        assertEquals(0x0E, c.cmds.get(c.cmds.size() - 1));
        f.startAutonomous(0);
        assertEquals(0x0A, c.cmds.get(c.cmds.size() - 1));
        assertThrows(IllegalArgumentException.class, () -> f.startAutonomous(3.0));
        assertThrows(IllegalArgumentException.class, () -> f.startAutonomous(-1));
        ChipMock c1 = new ChipMock(0x01, 0x32);
        new Si1132Full(c1).startAutonomous(0.5);
        assertEquals(0x80, c1.r(0x0A));
        assertEquals(0x3E, c1.r(0x08));
    }

    @Test
    void statusAndControl() throws IOException {
        ChipMock c = new ChipMock();
        Si1132Full f = new Si1132Full(c);
        f.pause();
        assertEquals(0x0A, c.cmds.get(c.cmds.size() - 1));
        f.force();
        assertEquals(0x06, c.cmds.get(c.cmds.size() - 1));
        assertTrue(f.measurementReady());
        c.registers().put(0x21, 0);
        assertFalse(f.measurementReady());
        c.registers().put(0x20, 0x05);
        assertArrayEquals(new int[]{5, 0}, f.readResponse());
        c.registers().put(0x20, 0x8C);
        assertArrayEquals(new int[]{0, 0x8C}, f.readResponse());
        f.setI2cAddress(0x61);
        assertEquals(0x61, c.params.get(0x00));
        assertEquals(0x02, c.cmds.get(c.cmds.size() - 1));
        f.reset();
        assertTrue(c.cmds.contains(0x01));
        assertTrue(wrote(c, 0x07, 0x17));
    }

    @Test
    void interruptRegisters() throws IOException {
        ChipMock c = new ChipMock();
        Si1132Full f = new Si1132Full(c);
        f.enableInterrupt();
        assertEquals(0x01, c.r(0x03));
        assertEquals(0x01, c.r(0x04));
        f.disableInterrupt();
        assertEquals(0x00, c.r(0x03));
        c.registers().put(0x21, 0x01);
        assertEquals(0x01, f.pollInterrupt());
        assertEquals(0, c.r(0x21));
        assertEquals(0, f.pollInterrupt());
    }

    @Test
    void interruptViaPin() throws IOException {
        ChipMock c = new ChipMock();
        FakePin pin = new FakePin();
        c.pin = pin;
        Si1132Full f = new Si1132Full(c);
        AtomicInteger got = new AtomicInteger(-1);
        f.onInterrupt(got::set);
        assertEquals(0x01, c.r(0x03));
        assertEquals(EdgeTrigger.FALLING, pin.trigger);
        c.registers().put(0x21, 0x01);
        pin.handler.onEdge();
        assertEquals(0x01, got.get());
        f.offInterrupt();
        assertNull(pin.handler);
        assertEquals(0x00, c.r(0x03));
    }

    @Test
    void interruptPollingFallback() throws Exception {
        ChipMock c = new ChipMock();
        Si1132Full f = new Si1132Full(c);
        AtomicInteger got = new AtomicInteger(-1);
        f.onInterrupt(got::set);
        c.registers().put(0x21, 0x01);
        long end = System.currentTimeMillis() + 1000;
        while (got.get() < 0 && System.currentTimeMillis() < end) Thread.sleep(5);
        f.offInterrupt();
        assertEquals(0x01, got.get());
    }
}
