package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FXOS8700Test {

    private static MockConnection newConnection() {
        MockConnection c = new MockConnection();
        c.setRegister(0x0D, 0xC7);
        return c;
    }

    private static FXOS8700Full newChip(MockConnection c) throws IOException {
        return new FXOS8700Full(c);
    }

    /** Data bytes of the last write to {@code reg}, or null. */
    private static byte[] lastWrite(MockConnection c, int reg) {
        byte[] out = null;
        for (byte[] w : c.writes()) {
            if (w.length >= 2 && (w[0] & 0xFF) == reg) {
                out = java.util.Arrays.copyOfRange(w, 1, w.length);
            }
        }
        return out;
    }

    private static int lastByte(MockConnection c, int reg) {
        byte[] v = lastWrite(c, reg);
        return v == null ? -1 : v[0] & 0xFF;
    }

    private static boolean wasRead(MockConnection c, int reg) {
        for (byte[] w : c.writes()) {
            if (w.length == 1 && (w[0] & 0xFF) == reg) {
                return true;
            }
        }
        return false;
    }

    /** Clears M_CTRL_REG1.m_ost once read, like the hardware. */
    private static class OneShotMock extends MockConnection {
        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            byte[] out = super.writeRead(data, n);
            if ((data[0] & 0xFF) == 0x5B) {
                setRegister(0x5B, registers().getOrDefault(0x5B, 0) & ~0x20);
            }
            return out;
        }
    }

    /** Returns shifted accelerometer data while CTRL_REG2.st is set. */
    private static class SelfTestMock extends MockConnection {
        private final int[] delta;

        SelfTestMock(int[] delta) {
            this.delta = delta;
        }

        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            byte[] out = super.writeRead(data, n);
            if ((data[0] & 0xFF) == 0x01 && n == 6 && (registers().getOrDefault(0x2B, 0) & 0x80) != 0) {
                for (int axis = 0; axis < 3; axis++) {
                    int raw = (delta[axis] << 2) & 0xFFFF;
                    out[2 * axis] = (byte) (raw >> 8);
                    out[2 * axis + 1] = (byte) raw;
                }
            }
            return out;
        }
    }

    @Test
    void initSequence() throws IOException {
        MockConnection c = newConnection();
        newChip(c);
        int[][] expect = {{0x2B, 0x40}, {0x2A, 0x00}, {0x5B, 0x1F}, {0x5C, 0x20}, {0x0E, 0x00}, {0x2B, 0x00}, {0x2A, 0x11}};
        int i = 0;
        for (byte[] w : c.writes()) {
            if (w.length == 2) {
                assertEquals(expect[i][0], w[0] & 0xFF);
                assertEquals(expect[i][1], w[1] & 0xFF);
                i++;
            }
        }
        assertEquals(7, i);
    }

    @Test
    void whoAmI() throws IOException {
        MockConnection pre = new MockConnection();
        pre.setRegister(0x0D, 0xC4);
        new FXOS8700Minimal(pre);
        MockConnection bad = new MockConnection();
        bad.setRegister(0x0D, 0x1A);
        assertThrows(IOException.class, () -> new FXOS8700Minimal(bad));
        assertEquals(-1, lastByte(bad, 0x2A));
    }

    @Test
    void toleratesNackOnResetWrite() throws IOException {
        MockConnection c = newConnection();
        c.failNextWrites(1, 1);
        new FXOS8700Minimal(c);
    }

    @Test
    void measurements() throws IOException {
        MockConnection c = newConnection();
        FXOS8700Full chip = newChip(c);
        c.setRegister(0x01, 0x40, 0x00, 0xC0, 0x00, 0x00, 0x04);
        c.setRegister(0x07, 0x00, 0x64, 0xFF, 0x9C, 0x01, 0xF4);
        c.setRegister(0x33, 0x00, 0x64, 0xFF, 0x9C, 0x01, 0xF4);
        double[] a = chip.readAccel();
        assertEquals(1.0, a[0], 1e-9);
        assertEquals(-1.0, a[1], 1e-9);
        assertEquals(1 / 4096.0, a[2], 1e-9);
        assertArrayEquals(new int[] {4096, -4096, 1}, chip.readRawAccel());
        double[] m = chip.readMag();
        assertEquals(10.0, m[0], 1e-9);
        assertEquals(-10.0, m[1], 1e-9);
        assertEquals(50.0, m[2], 1e-9);
        assertArrayEquals(new int[] {100, -100, 500}, chip.readRawMag());
        double[] h = chip.readHybrid();
        assertEquals(1.0, h[0], 1e-9);
        assertEquals(50.0, h[5], 1e-9);
        c.setRegister(0x0B, 0);
        chip.setRange(4);
        assertEquals(2.0, chip.readAccel()[0], 1e-9);
        chip.setRange(8);
        assertEquals(4.0, chip.readAccel()[0], 1e-9);
        assertEquals(0x02, lastByte(c, 0x0E));
        assertThrows(IllegalArgumentException.class, () -> chip.setRange(3));
        assertThrows(IllegalArgumentException.class, () -> chip.setLowNoise(true));
        c.setRegister(0x51, 0xFB);
        assertEquals(-5 * 0.96, chip.readTemperature(), 1e-9);
        chip.setMode(0);
        assertThrows(IllegalStateException.class, chip::readTemperature);
        assertThrows(IllegalStateException.class, chip::readHybrid);
    }

    @Test
    void standbyHandling() throws IOException {
        MockConnection c = newConnection();
        FXOS8700Full chip = newChip(c);
        int before = c.writes().size();
        chip.setDataRate(100);
        java.util.List<Integer> w = new java.util.ArrayList<>();
        for (byte[] x : c.writes().subList(before, c.writes().size())) {
            if (x.length == 2 && (x[0] & 0xFF) == 0x2A) {
                w.add(x[1] & 0xFF);
            }
        }
        assertEquals(java.util.List.of(0x10, 0x18, 0x19), w);
        assertThrows(IllegalArgumentException.class, () -> chip.setDataRate(77));
        chip.setDataRate(1.56);
        assertEquals(7, (lastByte(c, 0x2A) >> 3) & 7);
    }

    @Test
    void configuration() throws IOException {
        MockConnection c = newConnection();
        FXOS8700Full chip = newChip(c);
        chip.setLowNoise(true);
        assertEquals(0x05, lastByte(c, 0x2A) & 0x05);
        chip.setResolution(8);
        assertEquals(0x02, lastByte(c, 0x2A) & 0x02);
        c.setRegister(0x01, 0x40);
        c.setRegister(0x03, 0xC0);
        c.setRegister(0x05, 0x20);
        assertEquals(1.0, chip.readAccel()[0], 1e-9);
        chip.setResolution(14);
        chip.setAccelOversampling(2, 3);
        assertEquals(0x1A, lastByte(c, 0x2B) & 0x1B);
        chip.setMagOversampling(5, 2);
        assertEquals(5 << 2, lastByte(c, 0x5B) & 0x1C);
        assertEquals(2 << 4, lastByte(c, 0x5D) & 0x70);
        chip.setHighPassOutput(true, 2);
        assertEquals(0x10, lastByte(c, 0x0E) & 0x10);
        assertEquals(2, lastByte(c, 0x0F) & 0x03);
    }

    @Test
    void offsets() throws IOException {
        MockConnection c = newConnection();
        FXOS8700Full chip = newChip(c);
        chip.setAccelOffset(0.06, -0.06, 1.0);
        assertArrayEquals(new byte[] {30, (byte) 0xE2, 127}, lastWrite(c, 0x2F));
        double[] ao = chip.getAccelOffset();
        assertEquals(0.06, ao[0], 1e-9);
        assertEquals(0.254, ao[2], 1e-9);
        chip.setMagOffset(60.0, -60.0, 0.0);
        assertArrayEquals(new byte[] {0x04, (byte) 0xB0, (byte) 0xFB, 0x50, 0x00, 0x00}, lastWrite(c, 0x3F));
        double[] mo = chip.getMagOffset();
        assertEquals(60.0, mo[0], 1e-9);
        assertEquals(-60.0, mo[1], 1e-9);
    }

    @Test
    void magnetometerHelpers() throws IOException {
        OneShotMock c = new OneShotMock();
        c.setRegister(0x0D, 0xC7);
        FXOS8700Full chip = newChip(c);
        chip.setMagAutoCalibration(true);
        assertEquals(0x80, lastByte(c, 0x5B) & 0x80);
        chip.setMagAutoCalibration(false);
        assertEquals(0, lastByte(c, 0x5B) & 0x80);
        chip.resetMagMinMax();
        assertEquals(0x04, lastByte(c, 0x5C) & 0x04);
        chip.triggerMagReset();
        assertEquals(0x40, lastByte(c, 0x5B) & 0x40);
        chip.setMagReset(2);
        assertEquals(2, lastByte(c, 0x5C) & 0x03);
        c.setRegister(0x4B, 0xFF, 0x9C);
        c.setRegister(0x45, 0x00, 0x64);
        double[] mm = chip.readMagMinMax();
        assertEquals(-10.0, mm[0], 1e-9);
        assertEquals(10.0, mm[3], 1e-9);
        assertEquals(3, chip.measureMagOnce().length);
    }

    @Test
    void heading() throws IOException {
        MockConnection c = newConnection();
        FXOS8700Full chip = newChip(c);
        chip.setMode(0);
        c.setRegister(0x01, 0, 0, 0, 0, 0x40, 0);
        c.setRegister(0x33, 0x01, 0xF4, 0, 0, 0, 0);
        assertEquals(0.0, chip.heading(0.0), 1e-6);
        assertEquals(10.0, chip.heading(10.0), 1e-6);
        c.setRegister(0x33, 0x00, 0x00, 0xFE, 0x0C, 0, 0);
        assertEquals(90.0, chip.heading(0.0), 1e-6);
    }

    @Test
    void fifo() throws IOException {
        MockConnection c = newConnection();
        FXOS8700Full chip = newChip(c);
        c.setRegister(0x00, 0x42);
        c.setRegister(0x01, 0x40);
        chip.setFifo(1, 16);
        assertEquals(0x50, lastByte(c, 0x09));
        int before = c.writes().size();
        chip.setFifo(2, 8);
        java.util.List<Integer> f = new java.util.ArrayList<>();
        for (byte[] x : c.writes().subList(before, c.writes().size())) {
            if (x.length == 2 && (x[0] & 0xFF) == 0x09) {
                f.add(x[1] & 0xFF);
            }
        }
        assertEquals(java.util.List.of(0x00, 0x88), f);
        assertEquals(2, chip.fifoLevel());
        assertArrayEquals(new int[] {0, 1, 2}, chip.readFifoStatus());
        double[][] samples = chip.readFifo(0);
        assertEquals(2, samples.length);
        assertEquals(1.0, samples[0][0], 1e-9);
        assertThrows(IllegalArgumentException.class, () -> chip.setResolution(8));
        chip.setFifoTrigger(true, false, false, true);
        assertEquals(0x24, lastByte(c, 0x0A));
        assertThrows(IllegalArgumentException.class, () -> chip.setFifo(4, 0));
    }

    @Test
    void detectors() throws IOException {
        MockConnection c = newConnection();
        FXOS8700Full chip = newChip(c);
        chip.setFreefallMotion(true, 7, 0.3, 60.0, true, false);
        assertEquals(0xF8, lastByte(c, 0x15));
        assertEquals(5, lastByte(c, 0x17));
        assertEquals(6, lastByte(c, 0x18));
        chip.setFreefallMotion(false, 3, 0.2, 0.0, false, true);
        assertEquals(0x18, lastByte(c, 0x15));
        assertEquals(0x83, lastByte(c, 0x17));
        chip.setTransient(6, 0.5, 40.0, true, true);
        assertEquals(0x1D, lastByte(c, 0x1D));
        assertEquals(8, lastByte(c, 0x1F));
        assertEquals(4, lastByte(c, 0x20));
        chip.setPulse(1, 4, new double[] {0.5, 0.5, 1.0}, 30.0, 100.0, 300.0, true, false, true, true);
        assertEquals(0x61, lastByte(c, 0x21));
        assertArrayEquals(new byte[] {8, 8, 16}, lastWrite(c, 0x23));
        assertEquals(3, lastByte(c, 0x26));
        assertEquals(5, lastByte(c, 0x27));
        assertEquals(15, lastByte(c, 0x28));
        assertEquals(0x10, lastByte(c, 0x0F) & 0x30);
        chip.setPulse(1, 0, new double[] {0.5, 0.5, 0.5}, 30.0, 100.0, 300.0, false, false, false, false);
        assertEquals(12, lastByte(c, 0x26));
        assertEquals(0x20, lastByte(c, 0x0F) & 0x30);
        chip.setOrientation(true, 100.0, 45, 4, 4, 2);
        assertEquals(0xC0, lastByte(c, 0x11));
        assertEquals(10, lastByte(c, 0x12));
        assertEquals(0x84, lastByte(c, 0x14));
        assertEquals(0x84, lastByte(c, 0x13));
        assertThrows(IllegalArgumentException.class, () -> chip.setOrientation(true, 0.0, 75, 7, 4, 2));
        assertThrows(IllegalArgumentException.class, () -> chip.setOrientation(true, 0.0, 50, 4, 4, 2));
        c.setRegister(0x10, 0x80 | 0x40 | (3 << 1) | 1);
        assertArrayEquals(new int[] {1, 1, 3, 1}, chip.readOrientation());
        chip.setAutoSleep(true, 12.5, 10.0, FXOS8700Full.SOURCE_FF_MT | FXOS8700Full.SOURCE_PULSE);
        assertEquals(1, lastByte(c, 0x2A) >> 6);
        assertEquals(31, lastByte(c, 0x29));
        assertEquals(0x04, lastByte(c, 0x2B) & 0x04);
        assertEquals(0x18, lastByte(c, 0x2C) & 0x7C);
        assertThrows(IllegalArgumentException.class, () -> chip.setAutoSleep(true, 50.0, 10.0, 0x01));
        chip.setFifoGate(true);
        assertEquals(0x80, lastByte(c, 0x2C) & 0x80);
        chip.setInterruptPins(true, true);
        assertEquals(0x03, lastByte(c, 0x2C) & 0x03);
    }

    @Test
    void interrupts() throws IOException {
        MockConnection c = newConnection();
        FXOS8700Full chip = newChip(c);
        chip.enableInterrupt(FXOS8700Full.SOURCE_FF_MT | FXOS8700Full.SOURCE_ORIENTATION, 1);
        assertEquals(0x14, lastByte(c, 0x2D));
        assertEquals(0x14, lastByte(c, 0x2E));
        chip.enableInterrupt(FXOS8700Full.SOURCE_FF_MT, 2);
        assertEquals(0x10, lastByte(c, 0x2E));
        chip.disableInterrupt(FXOS8700Full.SOURCE_ORIENTATION);
        assertEquals(0x04, lastByte(c, 0x2D));
        assertThrows(IllegalArgumentException.class, () -> chip.enableInterrupt(1, 3));
        c.setRegister(0x0C, 0x14);
        assertEquals(0x14, chip.pollInterrupt());
        assertTrue(wasRead(c, 0x16) && wasRead(c, 0x10));
    }

    @Test
    void registersAndReset() throws IOException {
        MockConnection c = newConnection();
        FXOS8700Full chip = newChip(c);
        chip.writeRegister(0x5F, new byte[] {1, 2});
        assertArrayEquals(new byte[] {1, 2}, chip.readRegister(0x5F, 2));
        c.setRegister(0x00, 0x08);
        assertTrue(chip.newAccelData());
        assertFalse(chip.newMagData());
        c.setRegister(0x0B, 0x02);
        assertEquals(2, chip.readSystemMode());
        c.setRegister(0x0B, 0x00);
        chip.reset();
        assertThrows(IllegalStateException.class, chip::readHybrid);
        chip.init();
        chip.readHybrid();
    }

    @Test
    void selfTest() throws IOException {
        SelfTestMock c = new SelfTestMock(new int[] {200, 300, 1300});
        c.setRegister(0x0D, 0xC7);
        int[] r = newChip(c).selfTest();
        assertArrayEquals(new int[] {1, 200, 300, 1300}, r);
        assertEquals(0x11, lastByte(c, 0x2A));
        SelfTestMock weak = new SelfTestMock(new int[] {10, 10, 10});
        weak.setRegister(0x0D, 0xC7);
        assertEquals(0, newChip(weak).selfTest()[0]);
    }
}
