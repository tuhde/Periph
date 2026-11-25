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

class SSD1305Test {

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static byte[] last(MockConnection c) {
        return c.writes().get(c.writes().size() - 1);
    }

    private static final byte[] SETUP = bytes(0x00, 0xAE, 0xD5, 0x70, 0xA8, 0x3F, 0xD3, 0x00, 0x40, 0xA0, 0xC0, 0xDA,
            0x12, 0x81, 0x80, 0xD9, 0x22, 0xDB, 0x34, 0xAD, 0x8E, 0xA4, 0xA6, 0x20, 0x02);

    @Test
    void initSequence() throws Exception {
        MockConnection c = new MockConnection();
        SSD1305Minimal lcd = new SSD1305Minimal(c);
        assertEquals(128, lcd.width());
        assertEquals(64, lcd.height());
        assertArrayEquals(SETUP, c.writes().get(0));
        assertArrayEquals(bytes(0x00, 0xB0, 0x00, 0x10), c.writes().get(1));
        int[] sizes = {31, 31, 31, 31, 8};
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
        SSD1305Minimal lcd = new SSD1305Minimal(c);
        int n0 = c.writes().size();
        lcd.setPixel(3, 10, true);
        lcd.setPixel(127, 63, true);
        lcd.setPixel(128, 0, true);
        lcd.setPixel(-1, 5, true);
        assertEquals(n0, c.writes().size());
        lcd.show();
        assertEquals(48, c.writes().size() - n0);
        assertArrayEquals(bytes(0x00, 0xB1, 0x04, 0x10), c.writes().get(n0 + 6));
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
        new SSD1305Minimal(o, 128, 32, 0);
        assertEquals((byte) 0xDA, o.writes().get(0)[11]);
        assertEquals(0x02, o.writes().get(0)[12]);
        assertEquals((byte) 0xA8, o.writes().get(0)[4]);
        assertEquals(0x1F, o.writes().get(0)[5]);
        MockConnection o2 = new MockConnection();
        SSD1305Minimal narrow = new SSD1305Minimal(o2, 100, 64, 16);
        int n0 = o2.writes().size();
        narrow.show();
        assertArrayEquals(bytes(0x00, 0xB0, 0x00, 0x11), o2.writes().get(n0));
        for (int[] a : new int[][] {{133, 64, 0}, {128, 60, 0}, {128, 8, 0}, {128, 72, 0}, {128, 64, 5}, {0, 64, 0}}) {
            MockConnection bad = new MockConnection();
            assertThrows(IllegalArgumentException.class, () -> new SSD1305Minimal(bad, a[0], a[1], a[2]));
            assertTrue(bad.writes().isEmpty());
        }
    }

    @Test
    void commands() throws Exception {
        MockConnection fc = new MockConnection();
        SSD1305Full f = new SSD1305Full(fc);
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
        assertArrayEquals(bytes(0x00, 0xDB, 0x3C), last(fc));
        f.setDimMode(0x40, 0x20);
        assertArrayEquals(bytes(0x00, 0xAB, 0x00, 0x40, 0x20), last(fc));
        f.setDim(true);
        assertArrayEquals(bytes(0x00, 0xAC), last(fc));
        f.setDim(false);
        assertArrayEquals(bytes(0x00, 0xAF), last(fc));
        f.setBrightness(0x33);
        assertArrayEquals(bytes(0x00, 0x82, 0x33), last(fc));
        f.setLut(49, 63, 40, 31);
        assertArrayEquals(bytes(0x00, 0x91, 49, 63, 40, 31), last(fc));
        f.setBankColors(bytes(1, 2, 3, 4), null);
        assertArrayEquals(bytes(0x00, 0x92, 1, 2, 3, 4), last(fc));
        f.setBankColors(bytes(1, 2, 3, 4), bytes(5, 6, 7, 8));
        assertArrayEquals(bytes(0x00, 0x93, 5, 6, 7, 8), last(fc));
        f.setAreaColor(true, true);
        assertArrayEquals(bytes(0x00, 0xD8, 0x35), last(fc));
        f.setScrollHorizontal(true, 0, 7, 0, 1);
        assertArrayEquals(bytes(0x00, 0x26, 1, 0, 0, 7), last(fc));
        f.setScrollHorizontal(false, 1, 3, 4, 2);
        assertArrayEquals(bytes(0x00, 0x27, 2, 1, 4, 3), last(fc));
        f.setScrollVertical(true, 0, 7, 6, 1, 5);
        assertArrayEquals(bytes(0x00, 0x29, 1, 0, 6, 7, 5), last(fc));
        f.setScrollArea(8, 40);
        assertArrayEquals(bytes(0x00, 0xA3, 8, 40), last(fc));
        f.scrollStart();
        assertArrayEquals(bytes(0x00, 0x2F), last(fc));
        int ks = fc.writes().size();
        f.scrollStop();
        assertArrayEquals(bytes(0x00, 0x2E), fc.writes().get(ks));
        assertEquals(1 + 48, fc.writes().size() - ks);
        f.setAddressingMode(SSD1305Minimal.ADDR_HORIZONTAL);
        assertArrayEquals(bytes(0x00, 0x20, 0x00), last(fc));
        ks = fc.writes().size();
        f.show();
        assertArrayEquals(bytes(0x00, 0x21, 4, 131, 0x22, 0, 7), fc.writes().get(ks));
        int total = 0;
        for (byte[] w : fc.writes().subList(ks + 1, fc.writes().size())) total += w.length - 1;
        assertEquals(128 * 8, total);
        f.setAddressingMode(SSD1305Minimal.ADDR_VERTICAL);
        assertArrayEquals(bytes(0x00, 0x20, 0x01), last(fc));
        f.setPixel(0, 9, true);
        ks = fc.writes().size();
        f.show();
        assertEquals(0x00, fc.writes().get(ks + 1)[1]);
        assertEquals(0x02, fc.writes().get(ks + 1)[2]);
        f.setPixel(0, 9, false);
        f.setAddressingMode(SSD1305Minimal.ADDR_PAGE);
        assertArrayEquals(bytes(0x00, 0x20, 0x02), last(fc));
        assertThrows(IllegalArgumentException.class, () -> f.setAddressingMode(3));
        f.sendCommand(0xE3, 0xE3);
        assertArrayEquals(bytes(0x00, 0xE3, 0xE3), last(fc));
        int n0 = fc.writes().size();
        f.showPage(2);
        assertArrayEquals(bytes(0x00, 0xB2, 0x04, 0x10), fc.writes().get(n0));
        assertEquals(6, fc.writes().size() - n0);
        f.showPage(8);
        assertEquals(6, fc.writes().size() - n0);
    }

    @Test
    void drawing() throws Exception {
        SSD1305Full f = new SSD1305Full(new MockConnection());
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
    void reads() throws Exception {
        MockConnection rc = new MockConnection();
        SSD1305Full c = new SSD1305Full(rc);
        rc.queueRead(bytes(0x40));
        assertEquals(0x40, c.readStatus());
        assertArrayEquals(bytes(0x00), last(rc));
        rc.queueRead(bytes(0xEE, 0x11, 0x22, 0x33));
        int k = rc.writes().size();
        assertArrayEquals(bytes(0x11, 0x22, 0x33), c.readPage(2, 5, 3));
        assertArrayEquals(bytes(0x00, 0xB2, 0x05, 0x10), rc.writes().get(k));
        assertArrayEquals(bytes(0x40), rc.writes().get(k + 1));
    }

    @Test
    void spi() throws Exception {
        MockDisplayConnection dc = new MockDisplayConnection();
        SSD1305SpiMinimal lcd = new SSD1305SpiMinimal(dc);
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
        assertTrue(shown.get(1).startsWith("command:04"));
        assertTrue(shown.get(2).startsWith("command:10"));
        assertTrue(shown.get(3).startsWith("data:"));
        assertEquals(128 * 2, shown.get(3).length() - "data:".length());
        MockDisplayConnection dc2 = new MockDisplayConnection();
        SSD1305SpiFull c = new SSD1305SpiFull(dc2);
        assertThrows(UnsupportedOperationException.class, c::readStatus);
        assertThrows(UnsupportedOperationException.class, () -> c.readPage(0, 0, 2));
        int m = dc2.commands().size();
        c.setContrast(0x20);
        assertEquals(List.of(0x81, 0x20), dc2.commands().subList(m, dc2.commands().size()));
    }
}
