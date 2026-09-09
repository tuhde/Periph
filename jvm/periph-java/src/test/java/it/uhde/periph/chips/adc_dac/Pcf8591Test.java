package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pcf8591Test {

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        Pcf8591Full adc = new Pcf8591Full(connection);

        // readChannel(2): writes control byte CHN=2, reads 2 bytes; byte0 stale, byte1 fresh.
        connection.queueRead(new byte[]{0x11, 0x7F});
        assertEquals(0x7F, adc.readChannel(2));
        assertArrayEquals(new byte[]{0x02}, connection.writes().get(connection.writes().size() - 1));

        // readChannel clamps an out-of-range channel to 0.
        connection.queueRead(new byte[]{0x00, 0x55});
        assertEquals(0x55, adc.readChannel(9));
        assertArrayEquals(new byte[]{0x00}, connection.writes().get(connection.writes().size() - 1));

        // readAll(): writes control with AI=1 (0x04), reads 5 bytes, discards stale byte.
        connection.queueRead(new byte[]{0x00, 0x10, 0x20, 0x30, 0x40});
        assertArrayEquals(new int[]{0x10, 0x20, 0x30, 0x40}, adc.readAll());
        assertArrayEquals(new byte[]{0x04}, connection.writes().get(connection.writes().size() - 1));

        // configure(input_mode=3, auto_increment=true, dac_enabled=true) -> 0x74.
        adc.configure(3, true, true);
        assertArrayEquals(new byte[]{0x74}, connection.writes().get(connection.writes().size() - 1));

        // readChannelVoltage(0, vref=3.3, vagnd=0.0): raw=128.
        connection.queueRead(new byte[]{0x00, (byte) 128});
        double v = adc.readChannelVoltage(0, 3.3, 0.0);
        assertEquals(128 * 3.3 / 256.0, v, 1e-9);

        // readAllVoltage(vref=3.3, vagnd=0.0): raws [0, 64, 128, 255].
        connection.queueRead(new byte[]{0x00, 0, 64, (byte) 128, (byte) 255});
        double[] voltages = adc.readAllVoltage(3.3, 0.0);
        double[] expected = {0.0, 64 * 3.3 / 256.0, 128 * 3.3 / 256.0, 255 * 3.3 / 256.0};
        assertArrayEquals(expected, voltages, 1e-9);

        // readDifferential(1): raw byte 200 -> signed two's complement = -56.
        connection.queueRead(new byte[]{0x00, (byte) 200});
        assertEquals(-56, adc.readDifferential(1));

        // raw byte 100 (< 128) stays positive.
        connection.queueRead(new byte[]{0x00, 100});
        assertEquals(100, adc.readDifferential(1));

        // setDac(200): sets AOE=1, AI=0, writes [ctrl, value].
        adc.setDac(200);
        byte[] last = connection.writes().get(connection.writes().size() - 1);
        assertEquals((byte) 200, last[1]);
        assertTrue((last[0] & 0x40) != 0);
        assertEquals(0, last[0] & 0x04);

        // setDacVoltage(0.5) -> value = round(0.5*255) = 128.
        adc.setDacVoltage(0.5);
        assertEquals((byte) 128, connection.writes().get(connection.writes().size() - 1)[1]);

        // disableDac(): clears AOE bit.
        adc.disableDac();
        assertEquals(0, connection.writes().get(connection.writes().size() - 1)[0] & 0x40);
    }
}
