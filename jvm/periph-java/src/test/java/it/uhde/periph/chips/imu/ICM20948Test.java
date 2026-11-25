package it.uhde.periph.chips.imu;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ICM20948Test {

    /** I²C fake that models the ICM-20948 user banks (REG_BANK_SEL = 0x7F). */
    static class BankedMock extends MockConnection {
        int bank = 0;
        final Map<Integer, Integer> regs = new HashMap<>();
        final List<int[]> log = new ArrayList<>();   // {bank, byte0, byte1, ...}

        BankedMock() {
            set(0, 0x00, 0xEA);
            set(0, 0x17, 0x40);   // I2C_MST_STATUS: SLV4_DONE
        }

        void set(int b, int reg, int... values) {
            for (int i = 0; i < values.length; i++) regs.put((b << 8) | (reg + i), values[i] & 0xFF);
        }

        int get(int b, int reg) { return regs.getOrDefault((b << 8) | reg, 0); }

        @Override
        public void write(byte[] data) {
            int[] entry = new int[data.length + 1];
            entry[0] = bank;
            for (int i = 0; i < data.length; i++) entry[i + 1] = data[i] & 0xFF;
            log.add(entry);
            if ((data[0] & 0xFF) == 0x7F) {
                bank = (data[1] & 0xFF) >> 4;
                return;
            }
            for (int i = 1; i < data.length; i++) regs.put((bank << 8) | ((data[0] & 0xFF) + i - 1), data[i] & 0xFF);
        }

        @Override
        public byte[] writeRead(byte[] data, int n) {
            log.add(new int[]{bank, data[0] & 0xFF});
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) out[i] = (byte) get(bank, (data[0] & 0xFF) + i);
            return out;
        }

        /** Register writes (without bank selects) that landed in the given bank. */
        List<int[]> writesIn(int b) {
            List<int[]> out = new ArrayList<>();
            for (int[] e : log) {
                if (e[0] == b && e.length > 2 && e[1] != 0x7F) out.add(Arrays.copyOfRange(e, 1, e.length));
            }
            return out;
        }

        int[] lastIn(int b) {
            List<int[]> w = writesIn(b);
            return w.get(w.size() - 1);
        }

        boolean wrote(int b, int... bytes) {
            for (int[] w : writesIn(b)) if (Arrays.equals(w, bytes)) return true;
            return false;
        }
    }

    private static int[] s16(int v) { return new int[]{(v >> 8) & 0xFF, v & 0xFF}; }
    private static int[] s16le(int v) { return new int[]{v & 0xFF, (v >> 8) & 0xFF}; }

    private static int[] cat(int[]... parts) {
        return Arrays.stream(parts).flatMapToInt(Arrays::stream).toArray();
    }

    private static ICM20948Full make(BankedMock m) throws IOException {
        return new ICM20948Full(m);
    }

    @Test
    void initWritesSequence() throws IOException {
        BankedMock m = new BankedMock();
        make(m);
        int[][] expected = {
                {0, 0x7F, 0x00}, {0, 0x00}, {0, 0x06, 0x80}, {0, 0x06, 0x01}, {0, 0x07, 0x00},
                {0, 0x7F, 0x20}, {2, 0x01, 0x19}, {2, 0x7F, 0x00},
                {0, 0x7F, 0x20}, {2, 0x14, 0x19}, {2, 0x7F, 0x00},
                {0, 0x7F, 0x20}, {2, 0x00, 0x0A}, {2, 0x7F, 0x00},
                {0, 0x7F, 0x20}, {2, 0x10, 0x00}, {2, 0x7F, 0x00},
                {0, 0x7F, 0x20}, {2, 0x11, 0x0A}, {2, 0x7F, 0x00},
        };
        assertEquals(expected.length, m.log.size());
        for (int i = 0; i < expected.length; i++) assertArrayEquals(expected[i], m.log.get(i), "init write " + i);
        assertEquals(0, m.bank);
    }

    @Test
    void whoAmIMismatchThrows() {
        BankedMock m = new BankedMock();
        m.set(0, 0x00, 0x00);
        assertThrows(IOException.class, () -> make(m));
    }

    @Test
    void accelGyroAndConfiguration() throws IOException {
        BankedMock m = new BankedMock();
        ICM20948Full s = make(m);
        m.set(0, 0x2D, cat(s16(16384), s16(-8192), s16(4096)));
        double[] a = s.accel();
        assertEquals(9.80665, a[0], 1e-9);
        assertEquals(-4.903325, a[1], 1e-9);
        assertEquals(2.4516625, a[2], 1e-9);
        m.set(0, 0x33, cat(s16(131), s16(-131), s16(262)));
        double[] g = s.gyro();
        assertEquals(Math.toRadians(1), g[0], 1e-9);
        assertEquals(Math.toRadians(-1), g[1], 1e-9);
        assertEquals(Math.toRadians(2), g[2], 1e-9);

        s.configureGyro(2);
        assertArrayEquals(new int[]{0x01, 0x1D}, m.lastIn(2));
        m.set(0, 0x33, cat(s16(328), s16(0), s16(0)));
        assertEquals(Math.toRadians(10), s.gyro()[0], 1e-9);
        s.configureAccel(1);
        assertArrayEquals(new int[]{0x14, 0x1B}, m.lastIn(2));
        m.set(0, 0x2D, cat(s16(8192), s16(0), s16(0)));
        assertEquals(9.80665, s.accel()[0], 1e-9);

        s.configureFilters(5, 2, false, true);
        List<int[]> w2 = m.writesIn(2);
        assertArrayEquals(new int[]{0x01, (5 << 3) | (2 << 1)}, w2.get(w2.size() - 2));
        assertArrayEquals(new int[]{0x14, (2 << 3) | (1 << 1) | 1}, w2.get(w2.size() - 1));
        s.configureFilters(3, 3, true, true);

        s.configureSampleRate(9, 0x123);
        w2 = m.writesIn(2);
        assertArrayEquals(new int[]{0x00, 9}, w2.get(w2.size() - 3));
        assertArrayEquals(new int[]{0x10, 0x01}, w2.get(w2.size() - 2));
        assertArrayEquals(new int[]{0x11, 0x23}, w2.get(w2.size() - 1));
        assertEquals(0, m.bank);
    }

    @Test
    void burstRawAndTemperature() throws IOException {
        BankedMock m = new BankedMock();
        ICM20948Full s = make(m);
        s.configureAccel(1);
        s.configureGyro(2);
        m.set(0, 0x2D, cat(s16(8192), s16(0), s16(0), s16(262), s16(0), s16(0), s16(340)));
        ICM20948Full.Sample sample = s.readAll();
        assertEquals(9.80665, sample.accel[0], 1e-9);
        assertEquals(Math.toRadians(262 / 32.8), sample.gyro[0], 1e-9);
        assertEquals(340 / 333.87 + 21.0, sample.tempC, 1e-9);

        m.set(0, 0x39, s16(340));
        assertEquals(340 / 333.87 + 21.0, s.temperature(), 1e-9);
        m.set(0, 0x2D, cat(s16(100), s16(-200), s16(300)));
        assertArrayEquals(new int[]{100, -200, 300}, s.accelRaw());
        m.set(0, 0x33, cat(s16(-50), s16(60), s16(-70)));
        assertArrayEquals(new int[]{-50, 60, -70}, s.gyroRaw());
        m.set(0, 0x1A, 0x01);
        assertTrue(s.dataReady());
        m.set(0, 0x1A, 0x00);
        assertFalse(s.dataReady());
    }

    @Test
    void powerAndOffsets() throws IOException {
        BankedMock m = new BankedMock();
        ICM20948Full s = make(m);
        s.setSensors(false, true);
        assertArrayEquals(new int[]{0x07, 0x38}, m.lastIn(0));
        s.setSensors(true, false);
        assertArrayEquals(new int[]{0x07, 0x07}, m.lastIn(0));
        s.setSensors(true, true);
        assertArrayEquals(new int[]{0x07, 0x00}, m.lastIn(0));
        s.setSleep(true);
        assertArrayEquals(new int[]{0x06, 0x41}, m.lastIn(0));
        s.setSleep(false);
        assertArrayEquals(new int[]{0x06, 0x01}, m.lastIn(0));

        s.setGyroOffset(Math.toRadians(1), 0.0, Math.toRadians(-1));
        assertArrayEquals(new int[]{0x03, 0x00, 0x21, 0x00, 0x00, 0xFF, 0xDF}, m.lastIn(2));
        s.setAccelOffset(0x1234, -1, 0);
        List<int[]> w1 = m.writesIn(1);
        assertArrayEquals(new int[]{0x14, 0x24, 0x68}, w1.get(w1.size() - 3));
        assertArrayEquals(new int[]{0x17, 0xFF, 0xFE}, w1.get(w1.size() - 2));
        m.set(1, 0x14, 0x24, 0x68, 0, 0xFF, 0xFE, 0, 0x00, 0x02);
        assertArrayEquals(new int[]{0x1234, -1, 1}, s.getAccelOffset());
        m.set(1, 0x28, 0xF6);
        assertEquals(-10 * 0.079, s.readTimebaseCorrection(), 1e-9);
        assertEquals(0, m.bank);
    }

    @Test
    void fifo() throws IOException {
        BankedMock m = new BankedMock();
        ICM20948Full s = make(m);
        m.set(0, 0x70, 0x03, 0x45);
        assertEquals(0x345, s.fifoCount());
        m.set(0, 0x70, 0x00, 0x02);
        m.set(0, 0x72, 0xAA, 0xBB);
        assertArrayEquals(new byte[]{(byte) 0xAA, (byte) 0xBB}, s.readFifo(-1));
        assertArrayEquals(new byte[]{(byte) 0xAA}, s.readFifo(1));
        m.set(0, 0x70, 0x00, 0x00);
        assertEquals(0, s.readFifo(-1).length);

        s.enableFifo(true, true, false);
        assertTrue(m.wrote(0, 0x67, 0x1E));
        assertTrue(m.wrote(0, 0x03, 0x40));
        List<int[]> w0 = m.writesIn(0);
        assertArrayEquals(new int[]{0x68, 0x1F}, w0.get(w0.size() - 2));
        assertArrayEquals(new int[]{0x68, 0x00}, w0.get(w0.size() - 1));
        s.setFifoMode(true);
        assertArrayEquals(new int[]{0x69, 0x1F}, m.lastIn(0));
        s.setFifoMode(false);
        assertArrayEquals(new int[]{0x69, 0x00}, m.lastIn(0));
    }

    @Test
    void interruptsAndWakeOnMotion() throws IOException {
        BankedMock m = new BankedMock();
        ICM20948Full s = make(m);
        s.setInterruptPin(true, false, true, true);
        assertArrayEquals(new int[]{0x0F, 0xB0}, m.lastIn(0));
        s.enableInterrupt(ICM20948Full.SOURCE_DATA_READY);
        assertArrayEquals(new int[]{0x11, 0x01}, m.lastIn(0));
        s.enableInterrupt(ICM20948Full.SOURCE_PLL_READY);
        assertArrayEquals(new int[]{0x10, 0x04}, m.lastIn(0));
        s.disableInterrupt(ICM20948Full.SOURCE_PLL_READY);
        assertArrayEquals(new int[]{0x10, 0x00}, m.lastIn(0));
        s.enableInterrupt(ICM20948Full.SOURCE_FIFO_OVERFLOW);
        assertArrayEquals(new int[]{0x12, 0x1F}, m.lastIn(0));
        assertThrows(IllegalArgumentException.class, () -> s.enableInterrupt(0x80));

        m.set(0, 0x19, 0x09, 0x01, 0x02);
        assertEquals(0x01 | 0x02 | 0x04 | 0x10, s.pollInterrupt());
        m.set(0, 0x19, 0x04, 0x00, 0x00);
        assertEquals(0x08, s.pollInterrupt());

        s.setWakeOnMotion(200, true);
        assertTrue(m.wrote(2, 0x13, 50));
        assertTrue(m.wrote(2, 0x12, 0x03));
        assertArrayEquals(new int[]{0x10, 0x08}, m.lastIn(0));
    }

    @Test
    void lowPowerGuard() throws IOException {
        BankedMock m = new BankedMock();
        ICM20948Full s = make(m);
        m.set(0, 0x06, 0x01);
        s.setLowPower(true, true, 2, 3);
        assertTrue(m.wrote(0, 0x05, 0x70));
        assertArrayEquals(new int[]{0x06, 0x21}, m.lastIn(0));
        assertTrue(m.wrote(2, 0x15, 0x02) && m.wrote(2, 0x02, 0x03));
        m.log.clear();
        s.configureGyro(0);
        List<int[]> w0 = m.writesIn(0);
        assertArrayEquals(new int[]{0x06, 0x01}, w0.get(0));
        assertArrayEquals(new int[]{0x06, 0x21}, w0.get(w0.size() - 1));
        s.setLowPower(false, false, 0, 0);
        assertEquals(0, m.get(0, 0x06) & 0x20);
    }

    @Test
    void magnetometer() throws IOException {
        BankedMock m = new BankedMock();
        ICM20948Full s = make(m);
        assertThrows(IllegalStateException.class, s::mag);
        assertThrows(IllegalStateException.class, s::magRaw);
        assertThrows(IllegalStateException.class, s::magDataReady);

        m.set(3, 0x17, 0x09);   // slave-4 read returns WIA2 = 0x09
        s.enableMag();
        assertEquals(0, m.bank);
        assertTrue(m.wrote(3, 0x01, 0x07));
        assertTrue(m.wrote(3, 0x16, 0x01) && m.wrote(3, 0x14, 0x32));
        assertTrue(m.wrote(3, 0x16, 0x08) && m.wrote(3, 0x14, 0x31));
        List<int[]> w3 = m.writesIn(3);
        assertArrayEquals(new int[]{0x03, 0x8C}, w3.get(w3.size() - 3));
        assertArrayEquals(new int[]{0x04, 0x10}, w3.get(w3.size() - 2));
        assertArrayEquals(new int[]{0x05, 0x89}, w3.get(w3.size() - 1));
        assertEquals(0x20, m.get(0, 0x03) & 0x20);

        m.set(0, 0x3B, cat(new int[]{0x01}, s16le(1000), s16le(-500), s16le(250), new int[]{0x00, 0x00}));
        double[] mag = s.mag();
        assertEquals(150.0, mag[0], 1e-9);
        assertEquals(-75.0, mag[1], 1e-9);
        assertEquals(37.5, mag[2], 1e-9);
        assertArrayEquals(new int[]{1000, -500, 250}, s.magRaw());
        assertTrue(s.magDataReady());
        m.set(0, 0x3B, 0x00);
        assertFalse(s.magDataReady());
        m.set(0, 0x3B, cat(new int[]{0x01}, s16le(1), s16le(2), s16le(3), new int[]{0x00, 0x08}));
        assertThrows(IOException.class, s::mag);
        assertArrayEquals(new int[]{1, 2, 3}, s.magRaw());

        s.magSetMode(2);
        assertTrue(m.wrote(3, 0x16, 0x02));
        m.set(3, 0x17, 0x5A);
        assertEquals(0x5A, s.auxReadByte(0x1E, 0x0A));
        assertTrue(m.wrote(3, 0x13, 0x9E));
        s.auxWriteByte(0x1E, 0x0B, 0xA5);
        assertTrue(m.wrote(3, 0x13, 0x1E) && m.wrote(3, 0x16, 0xA5));
        assertEquals(0, m.bank);
    }

    @Test
    void magnetometerErrors() throws IOException {
        BankedMock wia = new BankedMock();   // WIA2 reads 0 -> mismatch
        ICM20948Full s1 = make(wia);
        assertThrows(IOException.class, s1::enableMag);

        BankedMock nack = new BankedMock();
        nack.set(0, 0x17, 0x10);
        ICM20948Full s2 = make(nack);
        assertThrows(IOException.class, () -> s2.auxReadByte(0x0C, 0x01));
    }

    @Test
    void selfTestAndReset() throws IOException {
        BankedMock m = new BankedMock();
        ICM20948Full s = make(m);
        double[][] r = s.selfTest();
        assertArrayEquals(new double[]{0, 0, 0}, r[0]);
        assertArrayEquals(new double[]{0, 0, 0}, r[1]);
        assertEquals(0, m.bank);
        s.reset();
        assertArrayEquals(new int[]{0x06, 0x80}, m.lastIn(0));
    }
}
