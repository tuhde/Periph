package it.uhde.periph.chips.environmental;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Aht21Test {

    // 6-byte measurement response encoding humidity=50.0%, temperature=50.0 degC:
    // rawRh = data[1]<<12 | data[2]<<4 | data[3]>>4      = 0x80000 (524288 / 2**20 * 100 = 50.0)
    // rawT  = (data[3]&0x0F)<<16 | data[4]<<8 | data[5]  = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
    private static final byte[] MEASUREMENT = {0x00, (byte) 0x80, 0x00, 0x08, 0x00, 0x00};

    private static boolean writesContain(MockConnection connection, byte[] expected) {
        return connection.writes().stream().anyMatch(w -> java.util.Arrays.equals(w, expected));
    }

    private static byte[] concat(byte[] a, byte b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + 1);
        out[a.length] = b;
        return out;
    }

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();

        // Status byte with both CAL (0x08) and the paired 0x10 bit set, so the
        // (status & 0x18) != 0x18 check in the constructor passes and no
        // soft-reset/recalibration path runs.
        connection.queueRead(new byte[]{0x18});

        Aht21Full sensor = new Aht21Full(connection);
        assertFalse(writesContain(connection, Aht21Minimal.CMD_SOFT_RESET), "init should not soft-reset");

        connection.queueRead(MEASUREMENT.clone());
        double[] data = sensor.read();
        assertTrue(writesContain(connection, Aht21Minimal.CMD_TRIGGER), "read() should trigger");
        assertEquals(50.0, data[0]);
        assertEquals(50.0, data[1]);

        connection.queueRead(MEASUREMENT.clone());
        assertEquals(50.0, sensor.readTemperature());

        connection.queueRead(MEASUREMENT.clone());
        assertEquals(50.0, sensor.readHumidity());

        // read_with_crc: same 6 bytes plus a correct CRC-8 (poly 0x31, init 0xFF) trailer.
        connection.queueRead(concat(MEASUREMENT.clone(), (byte) 0x8B));
        double[] withCrc = sensor.readWithCrc();
        assertEquals(50.0, withCrc[0]);
        assertEquals(50.0, withCrc[1]);
        assertEquals(1.0, withCrc[2]);

        connection.queueRead(concat(MEASUREMENT.clone(), (byte) 0x00));
        assertEquals(0.0, sensor.readWithCrc()[2]);

        sensor.softReset();
        assertTrue(writesContain(connection, Aht21Minimal.CMD_SOFT_RESET));

        connection.queueRead(new byte[]{0x08});
        assertTrue(sensor.isCalibrated());
        connection.queueRead(new byte[]{0x00});
        assertFalse(sensor.isCalibrated());

        connection.queueRead(new byte[]{(byte) 0x80});
        assertTrue(sensor.isBusy());
        connection.queueRead(new byte[]{0x00});
        assertFalse(sensor.isBusy());
    }
}
