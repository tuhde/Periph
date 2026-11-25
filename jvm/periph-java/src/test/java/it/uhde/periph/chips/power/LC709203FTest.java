package it.uhde.periph.chips.power;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LC709203FTest {

    /** Each command code holds one (low, high, crc) word; writes do not overlap neighbours. */
    static class WordMock extends MockConnection {
        final Map<Integer, byte[]> words = new HashMap<>();

        void put(int cmd, int value) {
            int lo = value & 0xFF, hi = (value >> 8) & 0xFF;
            words.put(cmd, new byte[]{(byte) lo, (byte) hi, (byte) LC709203FMinimal.crc8(0x16, cmd, 0x17, lo, hi)});
        }

        @Override
        public void write(byte[] data) throws IOException {
            writes().add(data.clone());
            if (data.length == 4) words.put(data[0] & 0xFF, Arrays.copyOfRange(data, 1, 4));
        }

        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            writes().add(data.clone());
            byte[] w = words.get(data[0] & 0xFF);
            return w == null ? new byte[n] : w.clone();
        }

        boolean wrote(int cmd, int lo, int hi) {
            for (byte[] w : writes()) {
                if (w.length == 4 && (w[0] & 0xFF) == cmd && (w[1] & 0xFF) == lo && (w[2] & 0xFF) == hi) return true;
            }
            return false;
        }

        boolean lastIs(int cmd, int lo, int hi) {
            byte[] w = writes().get(writes().size() - 1);
            return w.length == 4 && (w[0] & 0xFF) == cmd && (w[1] & 0xFF) == lo && (w[2] & 0xFF) == hi
                && (w[3] & 0xFF) == LC709203FMinimal.crc8(0x16, cmd, lo, hi);
        }
    }

    @Test
    void crcDatasheetExamples() {
        assertEquals(0x86, LC709203FMinimal.crc8(0x16, 0x09, 0x17, 0xC2, 0x0E));
        assertEquals(0x3B, LC709203FMinimal.crc8(0x16, 0x09, 0x55, 0xAA));
    }

    @Test
    void apaInterpolation() {
        assertEquals(0x14, LC709203FMinimal.apaFor(700));
        assertEquals(0x08, LC709203FMinimal.apaFor(50));
        assertEquals(0x36, LC709203FMinimal.apaFor(9000));
        assertEquals(0x19, LC709203FMinimal.apaFor(1000));
    }

    @Test
    void fullApi() throws Exception {
        WordMock conn = new WordMock();
        conn.put(0x13, 8);
        conn.put(0x14, 0);
        LC709203FFull sensor = new LC709203FFull(conn);

        assertTrue(conn.wrote(0x15, 0x01, 0x00));
        assertTrue(conn.wrote(0x0B, 0x19, 0x00));
        assertTrue(conn.wrote(0x12, 0x01, 0x00));
        assertTrue(conn.wrote(0x16, 0x00, 0x00));
        assertTrue(conn.wrote(0x08, 0xA6, 0x0B));

        conn.put(0x09, 3778);
        assertEquals(3.778, sensor.voltage(), 1e-9);
        conn.put(0x0F, 755);
        assertEquals(75.5, sensor.percent(), 1e-9);
        conn.put(0x08, 0x0BA6);
        assertEquals(25.0, sensor.temperature(), 0.06);

        conn.words.put(0x09, new byte[]{(byte) 0xC2, 0x0E, 0x00});
        assertThrows(IOException.class, sensor::voltage);

        sensor.setTemperature(100);
        assertTrue(conn.lastIs(0x08, 0x04, 0x0D));
        sensor.setTemperature(-50);
        assertTrue(conn.lastIs(0x08, 0xE4, 0x09));

        conn.put(0x0D, 42);
        assertEquals(42, sensor.rsoc());
        sensor.setRsoc(55);
        assertTrue(conn.lastIs(0x0D, 55, 0));
        sensor.initRsoc();
        assertTrue(conn.lastIs(0x07, 0x55, 0xAA));
        sensor.beforeRsoc();
        assertTrue(conn.lastIs(0x04, 0x55, 0xAA));

        sensor.useThermistor(3380);
        assertTrue(conn.wrote(0x06, 0x34, 0x0D) && conn.lastIs(0x16, 1, 0));
        conn.put(0x16, 1);
        assertTrue(sensor.isThermistorMode());
        sensor.useI2cTemperature(25.0);
        assertTrue(conn.lastIs(0x08, 0xA6, 0x0B));

        sensor.setCurrentDirection(LC709203FFull.DIRECTION_DISCHARGE);
        assertTrue(conn.lastIs(0x0A, 0xFF, 0xFF));
        assertThrows(IllegalArgumentException.class, () -> sensor.setCurrentDirection(5));

        sensor.setApa(0x2D);
        assertTrue(conn.lastIs(0x0B, 0x2D, 0));
        sensor.setApt(0x1234);
        assertTrue(conn.lastIs(0x0C, 0x34, 0x12));
        sensor.setProfile(0);
        assertTrue(conn.wrote(0x12, 0, 0) && conn.lastIs(0x15, 1, 0));

        conn.put(0x1A, 0x0301);
        assertEquals(0x0301, sensor.profileId());
        conn.put(0x11, 0x1234);
        assertEquals(0x1234, sensor.icVersion());

        sensor.sleep();
        assertTrue(conn.lastIs(0x15, 2, 0));
        conn.put(0x15, 2);
        assertTrue(sensor.isSleeping());
        conn.put(0x15, 1);
        assertFalse(sensor.isSleeping());

        sensor.setAlarmRsoc(20);
        sensor.setAlarmVoltage(3.5);
        assertTrue(conn.lastIs(0x14, 0xAC, 0x0D));
        conn.put(0x0D, 10);
        conn.put(0x09, 3400);
        assertEquals(3, sensor.pollInterrupt());
        conn.put(0x0D, 50);
        conn.put(0x09, 3900);
        assertEquals(0, sensor.pollInterrupt());
    }
}
