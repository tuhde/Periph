package it.uhde.periph.chips.display;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PCF8578Test {

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    private static byte[] cat(int[] head, int n, int fill) {
        byte[] out = new byte[head.length + n];
        for (int i = 0; i < head.length; i++) out[i] = (byte) head[i];
        for (int i = 0; i < n; i++) out[head.length + i] = (byte) fill;
        return out;
    }

    private static byte[] last(MockConnection c) {
        var w = c.writes();
        return w.get(w.size() - 1);
    }

    private static byte[] prev(MockConnection c) {
        var w = c.writes();
        return w.get(w.size() - 2);
    }

    @Test
    void initWritesClearThenDisplayOn() throws Exception {
        MockConnection c = new MockConnection();
        new PCF8578Full(c);
        assertArrayEquals(cat(new int[]{0xE0, 0xD2, 0xFC, 0xF8, 0x00}, 160, 0), c.writes().get(0));
        assertArrayEquals(b(0x56), c.writes().get(1));
        int[][] cases = {{8, 0x55}, {24, 0x57}, {32, 0x54}};
        for (int[] cs : cases) {
            MockConnection m = new MockConnection();
            new PCF8578Minimal(m, cs[0]);
            assertArrayEquals(b(cs[1]), m.writes().get(1), "rows " + cs[0]);
        }
        assertThrows(IllegalArgumentException.class, () -> new PCF8578Minimal(new MockConnection(), 12));
    }

    @Test
    void pixelsAndColumns() throws Exception {
        MockConnection c = new MockConnection();
        PCF8578Full lcd = new PCF8578Full(c);
        lcd.clear();
        assertArrayEquals(cat(new int[]{0xE0, 0xF8, 0x00}, 160, 0), last(c));

        lcd.setPixel(0, 0, true);
        assertArrayEquals(b(0xE0, 0xF0, 16, 0x01), last(c));
        lcd.setPixel(3, 9, true);
        assertArrayEquals(b(0xE0, 0xF1, 19, 0x02), last(c));
        lcd.setPixel(3, 10, true);
        assertArrayEquals(b(0xE0, 0xF1, 19, 0x06), last(c));
        lcd.setPixel(3, 9, false);
        assertArrayEquals(b(0xE0, 0xF1, 19, 0x04), last(c));

        int n = c.writes().size();
        lcd.setPixel(24, 0, true);
        lcd.setPixel(0, 16, true);
        lcd.setPixel(-1, 0, true);
        assertEquals(n, c.writes().size(), "out-of-range pixels are ignored");
        assertTrue(lcd.getPixel(0, 0));
        assertFalse(lcd.getPixel(3, 9));
        assertEquals(n, c.writes().size(), "getPixel reads the shadow");

        lcd.writeColumns(22, 0, b(1, 2, 3, 4));
        assertArrayEquals(b(0xE0, 0xF0, 38, 1, 2), last(c));
        assertThrows(IllegalArgumentException.class, () -> lcd.writeColumns(0, 2, b(1)));
    }

    @Test
    void drawing() throws Exception {
        MockConnection c = new MockConnection();
        PCF8578Full lcd = new PCF8578Full(c);
        lcd.fill(true);
        assertArrayEquals(cat(new int[]{0xE0, 0xF0, 16}, 24, 0xFF), prev(c));
        assertArrayEquals(cat(new int[]{0xE0, 0xF1, 16}, 24, 0xFF), last(c));
        lcd.fill(false);
        assertFalse(lcd.getPixel(5, 5));

        lcd.clear();
        int n = c.writes().size();
        lcd.drawLine(0, 0, 3, 0, true);
        assertEquals(n + 1, c.writes().size(), "line is one batched write");
        assertArrayEquals(b(0xE0, 0xF0, 16, 1, 1, 1, 1), last(c));

        lcd.clear();
        lcd.drawRect(0, 0, 3, 3, true, false);
        assertTrue(lcd.getPixel(0, 0) && lcd.getPixel(2, 2));
        assertFalse(lcd.getPixel(1, 1));
        lcd.drawRect(0, 0, 3, 3, true, true);
        assertTrue(lcd.getPixel(1, 1));

        lcd.showBitmap(2, 8, new byte[][]{b(0xAA, 0xBB)});
        assertArrayEquals(b(0xE0, 0xF1, 18, 0xAA, 0xBB), last(c));
        assertThrows(IllegalArgumentException.class, () -> lcd.showBitmap(0, 3, new byte[][]{b(1)}));
    }

    @Test
    void configuration() throws Exception {
        MockConnection c = new MockConnection();
        PCF8578Full lcd = new PCF8578Full(c);
        lcd.setDisplay(PCF8578Full.DISPLAY_BLANK);
        assertArrayEquals(b(0x52), last(c));
        lcd.setDisplay(PCF8578Full.DISPLAY_INVERSE);
        assertArrayEquals(b(0x5E), last(c));
        lcd.setDisplay(PCF8578Full.DISPLAY_ALL_ON);
        assertArrayEquals(b(0x5A), last(c));
        lcd.setDisplay(PCF8578Full.DISPLAY_NORMAL);
        assertArrayEquals(b(0x56), last(c));

        lcd.setStartBank(1);
        assertArrayEquals(b(0x7D), last(c));
        lcd.scroll(1);
        assertArrayEquals(b(0x7C), last(c));
        lcd.scroll(-1);
        assertArrayEquals(b(0x7D), last(c));
        assertThrows(IllegalArgumentException.class, () -> lcd.setStartBank(4));

        lcd.deviceSelect(5);
        assertArrayEquals(b(0x65), last(c));
        assertThrows(IllegalArgumentException.class, () -> lcd.deviceSelect(16));
        lcd.setPixel(0, 0, true);
        assertEquals((byte) 0xE0, last(c)[0], "pixel access restores subaddress 0");

        lcd.setMode(24, true);
        assertArrayEquals(b(0x57), last(c));
        lcd.setMode(16, false);
        assertArrayEquals(b(0x46), last(c));
        assertThrows(IllegalStateException.class, () -> lcd.setPixel(0, 0, true));
        assertThrows(IllegalStateException.class, () -> lcd.writeRam(0, 0, b(1), 0));
        lcd.setMode(16, true);

        lcd.reset();
        assertArrayEquals(cat(new int[]{0xE0, 0xD2, 0xFC, 0xF8, 0x00}, 160, 0), prev(c));
        assertArrayEquals(b(0x56), last(c));
    }

    @Test
    void rawRam() throws Exception {
        MockConnection c = new MockConnection();
        PCF8578Full lcd = new PCF8578Full(c);
        lcd.writeRam(10, 0, b(1, 2), PCF8578Full.ACCESS_CHARACTER);
        assertArrayEquals(b(0xE0, 0xF0, 10, 1, 2), last(c));
        lcd.writeRam(10, 2, b(1, 2, 3, 4), PCF8578Full.ACCESS_HALF_GRAPHIC);
        assertArrayEquals(b(0xE0, 0xF6, 10, 1, 2, 3, 4), last(c));
        lcd.writeRam(10, 0, b(1, 2, 3, 4, 5, 6, 7, 8), PCF8578Full.ACCESS_FULL_GRAPHIC);
        assertArrayEquals(b(0xE0, 0xF8, 10, 1, 2, 3, 4, 5, 6, 7, 8), last(c));
        assertThrows(IllegalArgumentException.class, () -> lcd.writeRam(39, 0, b(0, 0), 0));
        assertThrows(IllegalArgumentException.class, () -> lcd.writeRam(0, 4, b(1), 0));
        assertThrows(IllegalArgumentException.class, () -> lcd.writeRam(0, 1, b(1), 1));

        byte[] data = lcd.readRam(16, 0, 4, PCF8578Full.ACCESS_CHARACTER);
        assertArrayEquals(b(0xE0, 0xF0, 16), last(c));
        assertEquals(4, data.length);
        lcd.readRam(5, 2, 3, PCF8578Full.ACCESS_HALF_GRAPHIC);
        assertArrayEquals(b(0xE0, 0xF6, 5), last(c));
        assertTrue(Arrays.equals(b(0xE0, 0xF6, 5), last(c)));
    }
}
