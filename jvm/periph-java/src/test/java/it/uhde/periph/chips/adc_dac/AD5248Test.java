package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AD5248Test {

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    private static byte[] last(MockConnection c) {
        return c.writes().get(c.writes().size() - 1);
    }

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        AD5248Full chip = new AD5248Full(connection);
        assertTrue(connection.writes().isEmpty());

        // set_wiper(0, 100) -> [0x00, 100]; set_wiper(1, 200) -> [0x80, 200].
        chip.setWiper(0, 100);
        assertArrayEquals(b(0x00, 100), last(connection));
        chip.setWiper(1, 200);
        assertArrayEquals(b(0x80, 200), last(connection));
        chip.setWiper(0, 999);
        assertArrayEquals(b(0x00, 255), last(connection));
        chip.setWiper(0, -5);
        assertArrayEquals(b(0x00, 0), last(connection));
        assertThrows(IllegalArgumentException.class, () -> chip.setWiper(2, 0));

        // get_wiper sends the instruction byte alone, then reads one byte.
        chip.setWiper(0, 100);
        chip.setWiper(1, 200);
        int before = connection.writes().size();
        assertEquals(100, chip.getWiper(0));
        assertEquals(before + 1, connection.writes().size());
        assertArrayEquals(b(0x00), last(connection));
        assertEquals(200, chip.getWiper(1));

        // R = D/256*RAB + 2*RW.
        chip.setWiper(0, 128);
        assertEquals(5320.0, chip.getResistance(0), 1e-9);
        chip.setResistance(1, 5320.0);
        assertArrayEquals(b(0x80, 128), last(connection));
        chip.setResistance(1, 1.0);
        assertArrayEquals(b(0x80, 0), last(connection));
        chip.setResistance(1, 1e9);
        assertArrayEquals(b(0x80, 255), last(connection));

        chip.setBoth(10, 20);
        assertArrayEquals(b(0x00, 10), connection.writes().get(connection.writes().size() - 2));
        assertArrayEquals(b(0x80, 20), last(connection));
        chip.setRatio(0, 0.5);
        assertArrayEquals(b(0x00, 128), last(connection));
        chip.setRatio(0, 1.0);
        assertArrayEquals(b(0x00, 255), last(connection));
        chip.setWiper(1, 64);
        assertEquals(0.25, chip.getRatio(1), 1e-9);

        chip.setWiper(0, 250);
        assertEquals(255, chip.increment(0, 10));
        assertEquals(250, chip.increment(0, -5));

        // shutdown writes SD=1 with the current code; resume clears it.
        chip.setWiper(1, 77);
        assertFalse(chip.isShutdown(1));
        chip.shutdown(1);
        assertArrayEquals(b(0xC0, 77), last(connection));
        assertTrue(chip.isShutdown(1));
        assertFalse(chip.isShutdown(0));
        chip.setWiper(1, 99);
        assertArrayEquals(b(0xC0, 99), last(connection));
        chip.resume(1);
        assertArrayEquals(b(0x80, 99), last(connection));
        assertFalse(chip.isShutdown(1));

        chip.shutdown(0);
        chip.reset();
        assertArrayEquals(b(0x00, 0x80), connection.writes().get(connection.writes().size() - 2));
        assertArrayEquals(b(0x80, 0x80), last(connection));
        assertFalse(chip.isShutdown(0));

        int n = connection.writes().size();
        chip.setRab(50000.0, 100.0);
        assertEquals(n, connection.writes().size());
        chip.setWiper(0, 128);
        assertEquals(25200.0, chip.getResistance(0), 1e-9);
    }
}
