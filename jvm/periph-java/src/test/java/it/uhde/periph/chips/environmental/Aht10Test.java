package it.uhde.periph.chips.environmental;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Aht10Test {

    // 6-byte measurement response encoding humidity=50.0%, temperature=50.0 degC:
    // rawRh = data[1]<<12 | data[2]<<4 | data[3]>>4      = 0x80000 (524288 / 2**20 * 100 = 50.0)
    // rawT  = (data[3]&0x0F)<<16 | data[4]<<8 | data[5]  = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
    private static final byte[] MEASUREMENT = {0x08, (byte) 0x80, 0x00, 0x08, 0x00, 0x00};

    private static boolean writesContain(MockConnection connection, byte[] expected) {
        return connection.writes().stream().anyMatch(w -> java.util.Arrays.equals(w, expected));
    }

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();

        // CAL bit set after the init command: no soft-reset retry.
        connection.queueRead(new byte[]{0x08});

        Aht10Full sensor = new Aht10Full(connection);
        assertTrue(writesContain(connection, Aht10Minimal.CMD_INIT), "init should send 0xE1");
        assertFalse(writesContain(connection, Aht10Minimal.CMD_SOFT_RESET), "init should not soft-reset");

        connection.queueRead(MEASUREMENT.clone());
        double[] data = sensor.read();
        assertTrue(writesContain(connection, Aht10Minimal.CMD_TRIGGER), "read() should trigger");
        assertEquals(50.0, data[0]);
        assertEquals(50.0, data[1]);

        connection.queueRead(MEASUREMENT.clone());
        assertEquals(50.0, sensor.readTemperature());

        connection.queueRead(MEASUREMENT.clone());
        assertEquals(50.0, sensor.readHumidity());

        connection.queueRead(MEASUREMENT.clone());
        assertArrayEquals(new int[]{0x80000, 0x80000}, sensor.readRaw());

        // BUSY on the first read, cleared on the second.
        connection.queueRead(new byte[]{(byte) 0x88, (byte) 0x80, 0x00, 0x08, 0x00, 0x00});
        connection.queueRead(MEASUREMENT.clone());
        assertEquals(50.0, sensor.read()[0]);

        // Spec example: raw_t = 0x6B300 -> 33.74 degC
        connection.queueRead(new byte[]{0x08, (byte) 0x80, 0x00, 0x06, (byte) 0xB3, 0x00});
        assertEquals(33.74, sensor.readTemperature(), 0.01);

        connection.queueRead(MEASUREMENT.clone());
        assertEquals(36.7, sensor.dewPoint(), 0.1);

        connection.queueRead(new byte[]{0x08});
        assertEquals(0x08, sensor.readStatusByte());

        sensor.softReset();
        assertTrue(writesContain(connection, Aht10Minimal.CMD_SOFT_RESET));

        connection.queueRead(new byte[]{0x08});
        assertTrue(sensor.isCalibrated());
        connection.queueRead(new byte[]{0x00});
        assertFalse(sensor.isCalibrated());

        connection.queueRead(new byte[]{(byte) 0x80});
        assertTrue(sensor.isBusy());
        connection.queueRead(new byte[]{0x00});
        assertFalse(sensor.isBusy());
    }

    @Test
    void uncalibratedAfterRetryThrows() {
        MockConnection connection = new MockConnection();
        connection.queueRead(new byte[]{0x00});
        connection.queueRead(new byte[]{0x00});
        assertThrows(IOException.class, () -> new Aht10Full(connection));
        assertTrue(writesContain(connection, Aht10Minimal.CMD_SOFT_RESET));
    }
}
