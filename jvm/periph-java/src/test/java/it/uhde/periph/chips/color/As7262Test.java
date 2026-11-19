package it.uhde.periph.chips.color;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class As7262Test {

    /** AS7262 simulator: STATUS/WRITE/READ hardware registers over virtual registers. */
    static class Sim extends MockConnection {
        final int[] v = new int[0x2C];
        int pending = -1;
        int out = -1;
        boolean stall = false;
        final List<int[]> vwrites = new ArrayList<>();

        Sim() {
            v[0] = 0x40; v[1] = 0x3E; v[2] = 0x41; v[3] = 0x21; v[4] = 0x08; v[5] = 0xFF; v[6] = 25;
        }

        @Override
        public byte[] read(int reg, int length) {
            if (reg == 0x00) return new byte[]{(byte) (stall ? 0x02 : (out >= 0 ? 0x01 : 0x00))};
            if (reg == 0x02) {
                byte b = (byte) (out >= 0 ? out : 0);
                out = -1;
                return new byte[]{b};
            }
            return new byte[length];
        }

        @Override
        public void write(int reg, byte[] data) {
            int b = data[0] & 0xFF;
            if (pending >= 0) {
                int r = pending;
                pending = -1;
                vwrites.add(new int[]{r, b});
                if (r == 4) {
                    if ((b & 0x80) != 0) { v[4] = 0x08; v[5] = 0xFF; v[7] = 0; return; }
                    v[4] = b | (v[4] & 0x02);
                    if (((b >> 2) & 3) == 3) v[4] |= 0x02;
                } else {
                    v[r] = b;
                }
            } else if ((b & 0x80) != 0) {
                pending = b & 0x7F;
            } else {
                out = v[b];
                if (b >= 0x08) v[4] &= ~0x02;
            }
        }

        boolean wrote(int r, int val) {
            for (int[] w : vwrites) if (w[0] == r && w[1] == val) return true;
            return false;
        }

        void loadCal(float... vals) {
            for (int i = 0; i < vals.length; i++) {
                byte[] b = ByteBuffer.allocate(4).putFloat(vals[i]).array();
                for (int k = 0; k < 4; k++) v[0x14 + 4 * i + k] = b[k] & 0xFF;
            }
        }
    }

    private static As7262Full ready(Sim c) throws IOException {
        As7262Full s = new As7262Full(c);
        s.init();
        return s;
    }

    @Test
    void constructorMakesNoBusAccess() {
        Sim c = new Sim();
        new As7262Full(c);
        assertTrue(c.vwrites.isEmpty());
    }

    @Test
    void initWritesDefaults() throws IOException {
        Sim c = new Sim();
        As7262Minimal s = new As7262Minimal(c);
        s.init();
        assertEquals(0x3B, c.v[5]);
        assertEquals(2, (c.v[4] >> 4) & 3);
        assertEquals(0, c.v[7]);
        assertTrue(c.wrote(4, 0x80));
    }

    @Test
    void readReturnsCalibratedChannels() throws IOException {
        Sim c = new Sim();
        As7262Minimal s = new As7262Minimal(c);
        s.init();
        c.loadCal(1.0f, 2.5f, 3.0f, 4.0f, 5.0f, 6.5f);
        As7262Minimal.Channels ch = s.read();
        assertEquals(1.0f, ch.violet, 1e-4);
        assertEquals(2.5f, ch.blue, 1e-4);
        assertEquals(6.5f, ch.red, 1e-4);
        assertTrue(c.wrote(4, 0x2C));
        assertEquals(25.0, s.temperature(), 1e-9);
    }

    @Test
    void readRawAndChannels() throws IOException {
        Sim c = new Sim();
        As7262Full s = ready(c);
        for (int i = 0; i < 12; i++) c.v[0x08 + i] = (i % 2 == 0) ? 0x01 : 0x02;
        assertEquals(0x0102, s.readRaw().violet);
        assertEquals(0x0102, s.readChannelRaw(5));
        c.loadCal(0, 0, 0, 0, 0, 7.25f);
        assertEquals(7.25f, s.readChannel(5), 1e-4);
        assertThrows(IllegalArgumentException.class, () -> s.readChannel(6));
    }

    @Test
    void gainIntegrationAndMode() throws IOException {
        Sim c = new Sim();
        As7262Full s = ready(c);
        s.setGain(64);
        assertEquals(64.0, s.getGain());
        s.setGain(3.7);
        assertEquals(3.7, s.getGain());
        s.setGain(1);
        assertEquals(1.0, s.getGain());
        assertThrows(IllegalArgumentException.class, () -> s.setGain(5));
        s.setIntegrationTime(50);
        assertEquals(18, c.v[5]);
        assertEquals(50.4, s.getIntegrationTime(), 1e-6);
        assertThrows(IllegalArgumentException.class, () -> s.setIntegrationTime(1));
        assertThrows(IllegalArgumentException.class, () -> s.setIntegrationTime(800));
        s.setMode(2);
        assertEquals(2, s.getMode());
        s.setContinuous(0);
        assertEquals(0, s.getMode());
        assertThrows(IllegalArgumentException.class, () -> s.setContinuous(3));
        assertThrows(IllegalArgumentException.class, () -> s.setMode(4));
        s.setInterrupt(true);
        assertTrue((c.v[4] & 0x40) != 0);
        s.setInterrupt(false);
        assertEquals(0, c.v[4] & 0x40);
    }

    @Test
    void ledsKeepEachOther() throws IOException {
        Sim c = new Sim();
        As7262Full s = ready(c);
        s.setLed(true, 25);
        assertEquals(0x18, c.v[7]);
        s.setIndicatorLed(true, 4);
        assertEquals(0x18 | 0x05, c.v[7]);
        s.setLed(false, 100);
        assertEquals(0x30 | 0x05, c.v[7]);
        assertThrows(IllegalArgumentException.class, () -> s.setLed(true, 200));
        assertThrows(IllegalArgumentException.class, () -> s.setIndicatorLed(true, 9));
    }

    @Test
    void versionsAndVirtualAccess() throws IOException {
        Sim c = new Sim();
        As7262Full s = ready(c);
        assertArrayEquals(new int[]{0x40, 0x3E}, s.hardwareVersion());
        assertEquals(0x40, s.deviceType());
        assertArrayEquals(new int[]{2, ((0x21 & 0x0F) << 2) | 1, 1}, s.firmwareVersion());
        s.writeVirtual(0x05, 0x10);
        assertEquals(0x10, s.readVirtual(0x05));
        s.softReset();
        assertEquals(0xFF, c.v[5]);
    }

    @Test
    void measureDataReadyAndPoll() throws IOException {
        Sim c = new Sim();
        As7262Full s = ready(c);
        s.measure();
        assertTrue(s.dataReady());
        assertEquals(1, s.pollInterrupt());
    }

    @Test
    void interruptPollingFallbackDelivers() throws Exception {
        Sim c = new Sim();
        As7262Full s = ready(c);
        s.setIntegrationTime(10);
        s.measure();
        AtomicInteger got = new AtomicInteger();
        s.onInterrupt(got::set, null);
        long end = System.currentTimeMillis() + 2000;
        while (got.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10);
        s.offInterrupt();
        assertEquals(1, got.get());
        assertEquals(0, c.v[4] & 0x40);
    }

    @Test
    void handshakeTimesOut() throws IOException {
        Sim c = new Sim();
        As7262Full s = ready(c);
        c.stall = true;
        assertThrows(IOException.class, () -> s.readVirtual(0));
    }
}
