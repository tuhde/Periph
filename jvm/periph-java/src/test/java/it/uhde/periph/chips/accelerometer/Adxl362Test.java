package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.OutputPin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Adxl362Test {

    private static final int CMD_WRITE_REG = 0x0A;
    private static final int CMD_READ_REG  = 0x0B;

    /**
     * In-memory fake {@link Connection} for ADXL362 unit tests. ADXL362's SPI
     * framing is opcode+address(+data) -- WRITE 0x0A addr data, READ 0x0B
     * addr -- unlike {@code MockConnection}'s single-byte-is-the-address (or
     * fixed-width-address) convention, so the real target register is the
     * SECOND byte of a write/writeRead command, not the first.
     */
    private static class Adxl362MockConnection implements Connection {
        final Map<Integer, Integer> registers = new HashMap<>();
        final List<byte[]> writes = new ArrayList<>();

        void setRegister(int reg, int... values) {
            for (int i = 0; i < values.length; i++) registers.put(reg + i, values[i] & 0xFF);
        }

        @Override public void enable() {}
        @Override public void disable() {}
        @Override public boolean isEnabled() { return true; }
        @Override public InputPin intPin() { return null; }
        @Override public OutputPin enPin() { return null; }

        @Override
        public void write(byte[] data) {
            writes.add(data.clone());
            if (data.length >= 3 && (data[0] & 0xFF) == CMD_WRITE_REG) {
                int reg = data[1] & 0xFF;
                for (int i = 2; i < data.length; i++) registers.put(reg + i - 2, data[i] & 0xFF);
            }
        }

        @Override
        public byte[] read(int n) { return new byte[n]; }

        @Override
        public byte[] writeRead(byte[] data, int n) {
            writes.add(data.clone());
            int reg;
            if (data.length == 2 && (data[0] & 0xFF) == CMD_READ_REG) {
                reg = data[1] & 0xFF;
            } else if (data.length > 0) {
                reg = data[0] & 0xFF;
            } else {
                return new byte[n];
            }
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) out[i] = (byte) (int) registers.getOrDefault(reg + i, 0);
            return out;
        }

        @Override public void close() {}
    }

    private static Adxl362MockConnection newConnection() {
        Adxl362MockConnection c = new Adxl362MockConnection();
        c.setRegister(0x00, 0xAD, 0x1D, 0xF2, 0x01); // DEVID_AD, DEVID_MST, PARTID, REVID
        return c;
    }

    private static boolean lastWriteEquals(Adxl362MockConnection conn, int... bytes) {
        byte[] want = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) want[i] = (byte) bytes[i];
        return java.util.Arrays.equals(conn.writes.get(conn.writes.size() - 1), want);
    }

    private static boolean writeAtEquals(Adxl362MockConnection conn, int fromEnd, int... bytes) {
        byte[] want = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) want[i] = (byte) bytes[i];
        return java.util.Arrays.equals(conn.writes.get(conn.writes.size() - fromEnd), want);
    }

    @Test
    void constructionAndRead() throws IOException {
        Adxl362MockConnection conn = newConnection();
        Adxl362Minimal chip = new Adxl362Minimal(conn);
        assertTrue(writeAtEquals(conn, 2, 0x0A, 0x2C, 0x13), "init writes FILTER_CTL");
        assertTrue(lastWriteEquals(conn, 0x0A, 0x2D, 0x02), "init writes POWER_CTL");

        conn.setRegister(0x0E, 0x64, 0x00, 0xCE, 0x0F, 0xD0, 0x07); // x=100,y=-50,z=2000 raw
        float[] xyz = chip.read();
        assertEquals(0.1f, xyz[0], 1e-6f);
        assertEquals(-0.05f, xyz[1], 1e-6f);
        assertEquals(2.0f, xyz[2], 1e-6f);
    }

    @Test
    void deviceIdAndSoftReset() throws IOException {
        Adxl362MockConnection conn = newConnection();
        Adxl362Full full = new Adxl362Full(conn);

        conn.setRegister(0x00, 0xAD, 0x1D, 0xF2, 0x07);
        assertArrayEquals(new int[]{0xAD, 0x1D, 0xF2, 0x07}, full.deviceId());

        full.softReset();
        assertTrue(lastWriteEquals(conn, 0x0A, 0x1F, 0x52));
    }

    @Test
    void setRangeAndSetOdr() throws IOException {
        Adxl362MockConnection conn = newConnection();
        Adxl362Full full = new Adxl362Full(conn);

        conn.setRegister(0x2C, 0x13);
        full.setRange(4);
        assertTrue(lastWriteEquals(conn, 0x0A, 0x2C, 0x53));

        conn.setRegister(0x2C, 0x53);
        full.setRange(8);
        assertTrue(lastWriteEquals(conn, 0x0A, 0x2C, 0x93));

        conn.setRegister(0x2C, 0x93);
        full.setOdr(60); // nearest of 50/100 -> 50 Hz (code 0x02)
        assertTrue(lastWriteEquals(conn, 0x0A, 0x2C, 0x92));
    }

    @Test
    void read8bitTemperatureStatus() throws IOException {
        Adxl362MockConnection conn = newConnection();
        Adxl362Full full = new Adxl362Full(conn);
        conn.setRegister(0x2C, 0x92);
        full.setRange(8);

        conn.setRegister(0x08, 100, 206, 50); // x=100, y=-50 (0xCE), z=50
        float[] r8 = full.read8bit();
        float sens8 = 0.004255f * 16.0f;
        assertEquals(100 * sens8, r8[0], 1e-3f);
        assertEquals(-50 * sens8, r8[1], 1e-3f);
        assertEquals(50 * sens8, r8[2], 1e-3f);

        conn.setRegister(0x14, 0xAB, 0x01); // raw 427 = 30 C
        assertEquals(30.0f, full.temperature(), 0.01f);

        conn.setRegister(0x0B, 0x41); // AWAKE + DATA_READY
        assertEquals(0x41, full.status());
        assertTrue(full.awake());
        assertTrue(full.dataReady());
    }

    @Test
    void fifoConfigureAndRead() throws IOException {
        Adxl362MockConnection conn = newConnection();
        Adxl362Full full = new Adxl362Full(conn);

        conn.setRegister(0x0C, 0xFF, 0x01); // 0x1FF = 511
        assertEquals(0x1FF, full.fifoEntries());

        full.configureFifo(Adxl362Full.FIFO_STREAM, true, 300);
        assertTrue(writeAtEquals(conn, 2, 0x0A, 0x28, 0x0E));
        assertTrue(lastWriteEquals(conn, 0x0A, 0x29, 0x2C));

        conn.setRegister(0x0C, 2, 0); // 2 entries
        conn.setRegister(0x0D, 100, 0, 171, 193);
        float[][] entries = full.readFifo();
        assertEquals(2, entries.length);
        assertEquals(Adxl362Full.AXIS_X, (int) entries[0][0]);
        assertEquals(100 * 0.001f, entries[0][1], 1e-6f);
        assertEquals(Adxl362Full.AXIS_TEMP, (int) entries[1][0]);
        assertEquals(30.0f, entries[1][1], 0.01f);
    }

    @Test
    void activityThreshold11BitRegression() throws IOException {
        // Regression: THRESH_ACT_H/THRESH_INACT_H are documented as bits
        // [10:8] (3 bits, 11-bit total threshold) -- must not clamp to
        // 10-bit (0x3FF) or mask the H byte with 0x03.
        Adxl362MockConnection conn = newConnection();
        Adxl362Full full = new Adxl362Full(conn);
        conn.setRegister(0x2C, 0x92);
        full.setRange(2);

        conn.setRegister(0x27, 0x00);
        full.setActivityThreshold(1.5f, true);
        // raw = round(1.5 / 0.001) = 1500 = 0x5DC -> L=0xDC, H bits[10:8]=0x05.
        // writeAtEquals(conn, 2, ...) is the readReg(ACT_INACT_CTL) command, not a write.
        assertTrue(writeAtEquals(conn, 4, 0x0A, 0x20, 0xDC));
        assertTrue(writeAtEquals(conn, 3, 0x0A, 0x21, 0x05));
        assertTrue(lastWriteEquals(conn, 0x0A, 0x27, 0x02));
    }

    @Test
    void linkLoopInterruptAndSelfTest() throws IOException {
        Adxl362MockConnection conn = newConnection();
        Adxl362Full full = new Adxl362Full(conn);

        conn.setRegister(0x27, 0x05);
        full.setLinkLoopMode(Adxl362Full.LINKLOOP_LOOP);
        assertTrue(lastWriteEquals(conn, 0x0A, 0x27, 0x35));

        conn.setRegister(0x2A, 0x00);
        full.setInterrupt(1, Adxl362Full.SOURCE_AWAKE, true);
        assertTrue(lastWriteEquals(conn, 0x0A, 0x2A, 0x40));

        conn.setRegister(0x2E, 0x00);
        full.selfTest(true);
        assertTrue(lastWriteEquals(conn, 0x0A, 0x2E, 0x01));
    }
}
