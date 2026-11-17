package it.uhde.periph.chips.led;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HT16K33Test {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    private static byte[] lastWrite(MockConnection connection) {
        var writes = connection.writes();
        return writes.get(writes.size() - 1);
    }

    @Test
    void fullApi() throws Exception {
        MockConnection c = new MockConnection();
        HT16K33Full d = new HT16K33Full(c);

        assertArrayEquals(b(0x21), c.writes().get(0));
        assertArrayEquals(b(0xA0), c.writes().get(1));
        assertArrayEquals(b(0xEF), c.writes().get(2));
        assertArrayEquals(new byte[17], c.writes().get(3));
        assertArrayEquals(b(0x81), c.writes().get(4));

        d.setPixel(1, 9, true);
        d.show();
        byte[] exp = new byte[17];
        exp[1 + 3] = 0x02;
        assertArrayEquals(exp, lastWrite(c));
        assertTrue(d.getPixel(1, 9));
        assertFalse(d.getPixel(1, 8));
        d.setPixel(7, 15);
        d.setPixel(1, 9, false);
        assertFalse(d.getPixel(1, 9));
        assertTrue(d.getPixel(7, 15));
        assertThrows(IllegalArgumentException.class, () -> d.setPixel(8, 0));
        assertThrows(IllegalArgumentException.class, () -> d.setPixel(0, 16));

        d.clear();
        assertArrayEquals(new byte[17], lastWrite(c));
        d.writeRaw(b(0xAB, 0xCD), 4);
        assertArrayEquals(b(0x04, 0xAB, 0xCD), lastWrite(c));
        int n = c.writes().size();
        assertThrows(IllegalArgumentException.class, () -> d.writeRaw(b(0, 0, 0), 14));
        assertThrows(IllegalArgumentException.class, () -> d.writeRaw(b(), 0));
        assertEquals(n, c.writes().size());

        d.setBrightness(7);
        assertArrayEquals(b(0xE7), lastWrite(c));
        assertThrows(IllegalArgumentException.class, () -> d.setBrightness(16));

        d.setBlink(1);
        assertArrayEquals(b(0x85), lastWrite(c));
        d.setBlink(0.5);
        assertArrayEquals(b(0x87), lastWrite(c));
        d.setBlink(2);
        assertArrayEquals(b(0x83), lastWrite(c));
        assertThrows(IllegalArgumentException.class, () -> d.setBlink(3));
        d.displayOff();
        assertArrayEquals(b(0x82), lastWrite(c));
        d.displayOn();
        assertArrayEquals(b(0x83), lastWrite(c));
        d.setBlink(0);

        d.setIntOutput(true, false);
        assertArrayEquals(b(0xA1), lastWrite(c));
        d.setIntOutput(true, true);
        assertArrayEquals(b(0xA3), lastWrite(c));

        d.standby();
        assertArrayEquals(b(0x20), lastWrite(c));
        n = c.writes().size();
        d.wake();
        var w = c.writes();
        assertArrayEquals(b(0x21), w.get(n));
        assertArrayEquals(b(0xA3), w.get(n + 1));
        assertArrayEquals(b(0xE7), w.get(n + 2));
        assertEquals(17, w.get(n + 3).length);
        assertArrayEquals(b(0x81), w.get(n + 4));

        c.queueRead(b(0x01, 0x00, 0x00, 0x10, 0x00, 0x00));
        long keys = d.readKeys();
        assertArrayEquals(b(0x40), lastWrite(c));
        assertEquals(1L | (1L << 25), keys);
        c.queueRead(b(0x01));
        assertTrue(d.keyPressed());
        assertArrayEquals(b(0x60), lastWrite(c));
        c.queueRead(b(0x00));
        assertEquals(0L, d.pollInterrupt());
        c.queueRead(b(0x01));
        c.queueRead(b(0, 0, 0, 0, 0x01, 0));
        assertEquals(1L << 26, d.pollInterrupt());
    }
}
