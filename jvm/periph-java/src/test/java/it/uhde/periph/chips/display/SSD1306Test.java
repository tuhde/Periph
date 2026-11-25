package it.uhde.periph.chips.display;

import it.uhde.periph.connection.MockConnection;
import it.uhde.periph.connection.MockDisplayConnection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SSD1306Test {

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static byte[] last(MockConnection c) {
        return c.writes().get(c.writes().size() - 1);
    }

    private static final byte[] SETUP = bytes(0x00, 0xAE, 0xD5, 0x80, 0xA8, 0x3F, 0xD3, 0x00, 0x40, 0x8D, 0x14, 0x20, 0x02,
            0xA0, 0xC0, 0xDA, 0x12, 0x81, 0x7F, 0xD9, 0x22, 0xDB, 0x20, 0xA4, 0xA6);

    @Test
    void initSequence() throws Exception {
        MockConnection c = new MockConnection();
        SSD1306Minimal lcd = new SSD1306Minimal(c);
        assertEquals(128, lcd.width());
        assertEquals(64, lcd.height());
        assertArrayEquals(SETUP, c.writes().get(0));
        assertArrayEquals(bytes(0x00, 0xB0, 0x00, 0x10), c.writes().get(1));
        int[] sizes = {31, 31, 31, 31, 4};
        for (int i = 0; i < 5; i++) {
            byte[] w = c.writes().get(2 + i);
            assertEquals(0x40, w[0]);
            assertEquals(sizes[i] + 1, w.length);
            for (int k = 1; k < w.length; k++) assertEquals(0, w[k]);
        }
        assertEquals(1 + 8 * 6 + 1, c.writes().size());
        assertArrayEquals(bytes(0x00, 0xB7, 0x00, 0x10), c.writes().get(1 + 7 * 6));
        assertArrayEquals(bytes(0x00, 0xAF), last(c));
    }

    @Test
    void pixelsAndShow() throws Exception {
        MockConnection c = new MockConnection();
        SSD1306Minimal lcd = new SSD1306Minimal(c);
        int n0 = c.writes().size();
        lcd.setPixel(3, 10, true);
        lcd.setPixel(127, 63, true);
        lcd.setPixel(128, 0, true);
        lcd.setPixel(-1, 5, true);
        assertEquals(n0, c.writes().size());
        lcd.show();
        assertEquals(48, c.writes().size() - n0);
        assertArrayEquals(bytes(0x00, 0xB1, 0x00, 0x10), c.writes().get(n0 + 6));
        assertEquals(0x04, c.writes().get(n0 + 7)[1 + 3]);
        byte[] lastChunk = c.writes().get(n0 + 47);
        assertEquals((byte) 0x80, lastChunk[lastChunk.length - 1]);
        lcd.clear();
        n0 = c.writes().size();
        lcd.show();
        for (byte[] w : c.writes().subList(n0, c.writes().size())) {
            if (w[0] == 0x40) for (int k = 1; k < w.length; k++) assertEquals(0, w[k]);
        }
    }

    @Test
    void optionsAndValidation() throws Exception {
        MockConnection o = new MockConnection();
        new SSD1306Minimal(o, 128, 32, true);
        assertEquals((byte) 0x8D, o.writes().get(0)[9]);
        assertEquals(0x10, o.writes().get(0)[10]);
        assertEquals((byte) 0xDA, o.writes().get(0)[15]);
        assertEquals(0x02, o.writes().get(0)[16]);
        assertEquals((byte) 0xA8, o.writes().get(0)[4]);
        assertEquals(0x1F, o.writes().get(0)[5]);
        for (int[] a : new int[][] {{129, 64}, {128, 60}, {128, 8}, {128, 72}, {0, 64}}) {
            MockConnection bad = new MockConnection();
            assertThrows(IllegalArgumentException.class, () -> new SSD1306Minimal(bad, a[0], a[1], false));
            assertTrue(bad.writes().isEmpty());
        }
    }

    @Test
    void commands() throws Exception {
        MockConnection fc = new MockConnection();
        SSD1306Full f = new SSD1306Full(fc);
        f.setContrast(0x55);
        assertArrayEquals(bytes(0x00, 0x81, 0x55), last(fc));
        f.setDisplay(false);
        assertArrayEquals(bytes(0x00, 0xAE), last(fc));
        f.setDisplay(true);
        assertArrayEquals(bytes(0x00, 0xAF), last(fc));
        f.sleep();
        assertArrayEquals(bytes(0x00, 0xAE), last(fc));
        f.wake();
        assertArrayEquals(bytes(0x00, 0xAF), last(fc));
        f.setInvert(true);
        assertArrayEquals(bytes(0x00, 0xA7), last(fc));
        f.setInvert(false);
        assertArrayEquals(bytes(0x00, 0xA6), last(fc));
        f.setAllOn(true);
        assertArrayEquals(bytes(0x00, 0xA5), last(fc));
        f.setAllOn(false);
        assertArrayEquals(bytes(0x00, 0xA4), last(fc));
        f.setFlip(true, true);
        assertArrayEquals(bytes(0x00, 0xA1, 0xC8), last(fc));
        f.setFlip(false, true);
        assertArrayEquals(bytes(0x00, 0xA0, 0xC8), last(fc));
        f.setFlip(false, false);
        assertArrayEquals(bytes(0x00, 0xA0, 0xC0), last(fc));
        f.setStartLine(40);
        assertArrayEquals(bytes(0x00, 0x68), last(fc));
        f.setDisplayOffset(12);
        assertArrayEquals(bytes(0x00, 0xD3, 12), last(fc));
        f.setMultiplex(48);
        assertArrayEquals(bytes(0x00, 0xA8, 47), last(fc));
        f.setClock(3, 8);
        assertArrayEquals(bytes(0x00, 0xD5, 0x82), last(fc));
        f.setPrecharge(2, 5);
        assertArrayEquals(bytes(0x00, 0xD9, 0x52), last(fc));
        f.setComConfig(false, false);
        assertArrayEquals(bytes(0x00, 0xDA, 0x02), last(fc));
        f.setComConfig(true, false);
        assertArrayEquals(bytes(0x00, 0xDA, 0x12), last(fc));
        f.setComConfig(true, true);
        assertArrayEquals(bytes(0x00, 0xDA, 0x32), last(fc));
        f.setVcomh(0xFF);
        assertArrayEquals(bytes(0x00, 0xDB, 0x70), last(fc));
        f.setChargePump(true);
        assertArrayEquals(bytes(0x00, 0x8D, 0x14), last(fc));
        f.setChargePump(false);
        assertArrayEquals(bytes(0x00, 0x8D, 0x10), last(fc));
        f.setScrollHorizontal(true, 0, 7, 7);
        assertArrayEquals(bytes(0x00, 0x26, 0x00, 0, 7, 7, 0x00, 0xFF), last(fc));
        f.setScrollHorizontal(false, 1, 3, 4);
        assertArrayEquals(bytes(0x00, 0x27, 0x00, 1, 4, 3, 0x00, 0xFF), last(fc));
        f.setScrollVertical(true, 0, 7, 6, 5);
        assertArrayEquals(bytes(0x00, 0x29, 0x00, 0, 6, 7, 5), last(fc));
        f.setScrollArea(8, 40);
        assertArrayEquals(bytes(0x00, 0xA3, 8, 40), last(fc));
        f.sendCommand(0xE3, 0xE3);
        assertArrayEquals(bytes(0x00, 0xE3, 0xE3), last(fc));
    }

    @Test
    void scrollingBlocksRamAccess() throws Exception {
        MockConnection fc = new MockConnection();
        SSD1306Full f = new SSD1306Full(fc);
        f.scrollStart();
        assertArrayEquals(bytes(0x00, 0x2F), last(fc));
        int n0 = fc.writes().size();
        assertThrows(IllegalStateException.class, f::show);
        assertThrows(IllegalStateException.class, () -> f.showPage(0));
        assertEquals(n0, fc.writes().size());
        f.scrollStop();
        assertArrayEquals(bytes(0x00, 0x2E), fc.writes().get(n0));
        assertEquals(1 + 48, fc.writes().size() - n0);
    }

    @Test
    void addressingModesAndShowPage() throws Exception {
        MockConnection fc = new MockConnection();
        SSD1306Full f = new SSD1306Full(fc);
        f.setAddressingMode(SSD1306Minimal.ADDR_HORIZONTAL);
        assertArrayEquals(bytes(0x00, 0x20, 0x00), last(fc));
        int n0 = fc.writes().size();
        f.show();
        assertArrayEquals(bytes(0x00, 0x21, 0, 127, 0x22, 0, 7), fc.writes().get(n0));
        int total = 0;
        for (byte[] w : fc.writes().subList(n0 + 1, fc.writes().size())) total += w.length - 1;
        assertEquals(128 * 8, total);
        f.setAddressingMode(SSD1306Minimal.ADDR_VERTICAL);
        assertArrayEquals(bytes(0x00, 0x20, 0x01), last(fc));
        f.setPixel(0, 9, true);
        n0 = fc.writes().size();
        f.show();
        assertEquals(0x00, fc.writes().get(n0 + 1)[1]);
        assertEquals(0x02, fc.writes().get(n0 + 1)[2]);
        f.setPixel(0, 9, false);
        n0 = fc.writes().size();
        f.showPage(2);
        assertArrayEquals(bytes(0x00, 0x21, 0, 127, 0x22, 2, 2), fc.writes().get(n0));
        f.setAddressingMode(SSD1306Minimal.ADDR_PAGE);
        assertArrayEquals(bytes(0x00, 0x20, 0x02), last(fc));
        assertThrows(IllegalArgumentException.class, () -> f.setAddressingMode(3));
        n0 = fc.writes().size();
        f.showPage(2);
        assertArrayEquals(bytes(0x00, 0xB2, 0x00, 0x10), fc.writes().get(n0));
        assertEquals(6, fc.writes().size() - n0);
        f.showPage(8);
        assertEquals(6, fc.writes().size() - n0);
    }

    @Test
    void drawing() throws Exception {
        SSD1306Full f = new SSD1306Full(new MockConnection());
        f.fill(true);
        assertTrue(f.getPixel(0, 0) && f.getPixel(127, 63));
        f.fill(false);
        assertFalse(f.getPixel(0, 0) || f.getPixel(-1, 0) || f.getPixel(0, 64));
        f.drawLine(0, 0, 9, 9, true);
        assertTrue(f.getPixel(0, 0) && f.getPixel(5, 5) && f.getPixel(9, 9) && !f.getPixel(5, 6));
        f.fill(false);
        f.drawRect(10, 20, 5, 4, true, false);
        assertTrue(f.getPixel(10, 20) && f.getPixel(14, 23) && f.getPixel(12, 20) && !f.getPixel(12, 21));
        f.fill(false);
        f.drawRect(10, 20, 3, 3, true, true);
        assertTrue(f.getPixel(11, 21) && f.getPixel(12, 22) && !f.getPixel(13, 22));
        f.fill(false);
        f.blit(4, 8, bytes(0xFF, 0x0F, 0x01, 0x80), 2);
        assertTrue(f.getPixel(4, 8) && f.getPixel(4, 15) && f.getPixel(5, 8) && !f.getPixel(5, 12)
                && f.getPixel(5, 11) && f.getPixel(4, 16) && f.getPixel(5, 23) && !f.getPixel(4, 17));
        assertThrows(IllegalArgumentException.class, () -> f.blit(4, 3, bytes(1, 2), 1));
    }

    @Test
    void spi() throws Exception {
        MockDisplayConnection dc = new MockDisplayConnection();
        SSD1306SpiMinimal lcd = new SSD1306SpiMinimal(dc);
        assertEquals("reset", dc.events().get(0));
        List<Integer> cmds = dc.commands();
        for (int i = 0; i < 24; i++) assertEquals(SETUP[i + 1] & 0xFF, cmds.get(i));
        assertEquals(0xAF, cmds.get(cmds.size() - 1));
        for (int i = 0; i < cmds.size(); i++) assertEquals(0, dc.commandData(i).length);
        assertEquals(8, dc.events().stream().filter(e -> e.startsWith("data:")).count());
        int n = dc.events().size();
        lcd.setPixel(5, 0, true);
        lcd.show();
        List<String> shown = dc.events().subList(n, dc.events().size());
        assertTrue(shown.get(0).startsWith("command:b0"));
        assertTrue(shown.get(1).startsWith("command:00"));
        assertTrue(shown.get(2).startsWith("command:10"));
        assertTrue(shown.get(3).startsWith("data:"));
        assertEquals(128 * 2, shown.get(3).length() - "data:".length());
        MockDisplayConnection dc2 = new MockDisplayConnection();
        SSD1306SpiFull c = new SSD1306SpiFull(dc2);
        int m = dc2.commands().size();
        c.setContrast(0x20);
        assertEquals(List.of(0x81, 0x20), dc2.commands().subList(m, dc2.commands().size()));
        c.scrollStart();
        assertThrows(IllegalStateException.class, c::show);
    }
}
