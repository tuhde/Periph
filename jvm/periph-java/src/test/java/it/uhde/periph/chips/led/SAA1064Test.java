package it.uhde.periph.chips.led;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SAA1064Test {

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
        SAA1064Full d = new SAA1064Full(c);

        assertArrayEquals(b(0x00, 0x47, 0, 0, 0, 0), c.writes().get(0));

        d.showDigits(new int[]{1, 2, 3, 4});
        assertArrayEquals(b(0x01, 0x06, 0x5B, 0x4F, 0x66), lastWrite(c));
        d.showDigits(new int[]{SAA1064Minimal.BLANK, 0, 9, 8});
        assertArrayEquals(b(0x01, 0x00, 0x3F, 0x6F, 0x7F), lastWrite(c));
        assertThrows(IllegalArgumentException.class, () -> d.showDigits(new int[]{10, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> d.showDigits(new int[]{1, 2, 3}));

        d.showNumber(42);
        assertArrayEquals(b(0x01, 0x00, 0x00, 0x66, 0x5B), lastWrite(c));
        d.showNumber(0);
        assertArrayEquals(b(0x01, 0x00, 0x00, 0x00, 0x3F), lastWrite(c));
        d.showNumber(9999);
        assertArrayEquals(b(0x01, 0x6F, 0x6F, 0x6F, 0x6F), lastWrite(c));
        assertThrows(IllegalArgumentException.class, () -> d.showNumber(10000));
        assertThrows(IllegalArgumentException.class, () -> d.showNumber(-1));

        d.clear();
        assertArrayEquals(b(0x01, 0, 0, 0, 0), lastWrite(c));
        d.writeRaw(new int[]{0xAA, 0x55}, 2);
        assertArrayEquals(b(0x02, 0xAA, 0x55), lastWrite(c));
        assertThrows(IllegalArgumentException.class, () -> d.writeRaw(new int[]{1, 2, 3}, 3));
        assertThrows(IllegalArgumentException.class, () -> d.writeRaw(new int[]{1}, 0));
        assertThrows(IllegalArgumentException.class, () -> d.writeRaw(new int[]{}, 1));

        d.setMode(false);
        assertArrayEquals(b(0x00, 0x46), lastWrite(c));
        d.setMode(true);
        assertArrayEquals(b(0x00, 0x47), lastWrite(c));
        d.blank(true, false);
        assertArrayEquals(b(0x00, 0x45), lastWrite(c));
        d.blank(true, true);
        assertArrayEquals(b(0x00, 0x41), lastWrite(c));
        d.blank(false, false);
        assertArrayEquals(b(0x00, 0x47), lastWrite(c));
        d.setCurrent(21);
        assertArrayEquals(b(0x00, 0x77), lastWrite(c));
        d.setCurrent(0);
        assertArrayEquals(b(0x00, 0x07), lastWrite(c));
        d.setCurrent(12);
        assertArrayEquals(b(0x00, 0x47), lastWrite(c));
        assertThrows(IllegalArgumentException.class, () -> d.setCurrent(10));
        d.segmentTest(true);
        assertArrayEquals(b(0x00, 0x4F), lastWrite(c));
        d.segmentTest(false);
        assertArrayEquals(b(0x00, 0x47), lastWrite(c));
        d.writeControl(0xFF);
        assertArrayEquals(b(0x00, 0x7F), lastWrite(c));
        d.writeControl(0x47);

        d.showDigit(2, 7, true);
        assertArrayEquals(b(0x02, 0x87), lastWrite(c));
        d.setDecimalPoint(2, false);
        assertArrayEquals(b(0x02, 0x07), lastWrite(c));
        d.setDecimalPoint(3, true);
        assertArrayEquals(b(0x03, 0xD5), lastWrite(c));
        assertThrows(IllegalArgumentException.class, () -> d.showDigit(5, 1));
        assertThrows(IllegalArgumentException.class, () -> d.setDecimalPoint(0, true));

        d.setSegmentMap(Map.of("a", 7, "b", 6, "c", 5, "d", 4, "e", 3, "f", 2, "g", 1, "dp", 0));
        d.showDigits(new int[]{1, 1, 1, 1});
        assertArrayEquals(b(0x01, 0x60, 0x60, 0x60, 0x60), lastWrite(c));
        assertThrows(IllegalArgumentException.class, () -> d.setSegmentMap(Map.of("a", 0)));
        assertThrows(IllegalArgumentException.class, () -> d.setSegmentMap(Map.of("a", 8, "b", 1, "c", 2, "d", 3, "e", 4, "f", 5, "g", 6, "dp", 7)));

        c.queueRead(b(0x80));
        assertEquals(0x80, d.readStatus());
        c.queueRead(b(0x80));
        assertTrue(d.powerResetFlag());
        c.queueRead(b(0x00));
        assertFalse(d.powerResetFlag());
    }
}
