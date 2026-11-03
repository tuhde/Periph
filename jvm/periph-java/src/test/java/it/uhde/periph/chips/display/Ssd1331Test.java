package it.uhde.periph.chips.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.uhde.periph.connection.MockDisplayConnection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class Ssd1331Test {

    /** Command/parameter bytes as ints (asserting none carries parameters); data events as -length. */
    private static List<Integer> stream(MockDisplayConnection m, int from) {
        List<Integer> out = new ArrayList<>();
        List<String> ev = m.events();
        for (int i = from; i < ev.size(); i++) {
            String e = ev.get(i);
            if (e.startsWith("command:")) {
                String[] p = e.split(":", -1);
                assertEquals("", p[2], "parameters must be separate commands (D/C# low)");
                out.add(Integer.parseInt(p[1], 16));
            } else if (e.startsWith("data:")) {
                out.add(-(e.length() - 5) / 2);
            }
        }
        return out;
    }

    private static List<Integer> l(int... v) {
        List<Integer> out = new ArrayList<>();
        for (int x : v) out.add(x);
        return out;
    }

    private static int dataBytes(MockDisplayConnection m, int from) {
        int n = 0;
        for (int i = from; i < m.events().size(); i++) {
            String e = m.events().get(i);
            if (e.startsWith("data:")) n += (e.length() - 5) / 2;
        }
        return n;
    }

    private static Ssd1331Full full(MockDisplayConnection m) throws IOException {
        return new Ssd1331Full(m);
    }

    @Test
    void initSequenceVendor() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        Ssd1331Minimal d = new Ssd1331Minimal(m);
        assertEquals("reset", m.events().get(0));
        assertEquals("delay:100", m.events().get(1));
        assertEquals(l(0xAE, 0x81, 0xFF, 0x82, 0xFF, 0x83, 0xFF, 0x87, 0x06, 0x8A, 0x64, 0x8B, 0x78, 0x8C, 0x64,
                0xA0, 0x72, 0xA1, 0x00, 0xA2, 0x00, 0xA4, 0xA8, 0x3F, 0xAD, 0x8E, 0xB0, 0x0B, 0xB1, 0x31,
                0xB3, 0xF0, 0xBB, 0x3A, 0xBE, 0x3E, 0x2E, 0x25, 0x00, 0x00, 0x5F, 0x3F, 0xAF), stream(m, 0));
        assertEquals("delay:100", m.events().get(m.events().size() - 2));
        assertEquals(0, dataBytes(m, 0));
        assertEquals(96, d.width());
        assertEquals(64, d.height());
    }

    @Test
    void framebuffer() throws IOException {
        Ssd1331Full d = full(new MockDisplayConnection());
        d.setPixel(0, 0, Ssd1331Minimal.RED);
        d.setPixel(95, 63, Ssd1331Minimal.BLUE);
        d.setPixel(1, 2, 0x1234);
        assertEquals(Ssd1331Minimal.RED, d.getPixel(0, 0));
        assertEquals(0x1234, d.getPixel(1, 2));
        assertEquals((byte) 0xF8, d.fb[0]);
        assertEquals(0x1F, d.fb[12287]);
        d.setPixel(-1, 0, 1);
        d.setPixel(0, -1, 1);
        d.setPixel(96, 0, 1);
        d.setPixel(0, 64, 1);
        assertEquals(0, d.getPixel(99, 99));
        assertEquals((byte) 0xF8, d.fb[0]);
        d.clear(Ssd1331Minimal.GREEN);
        assertEquals(Ssd1331Minimal.GREEN, d.getPixel(0, 0));
        assertEquals(Ssd1331Minimal.GREEN, d.getPixel(95, 63));
        assertEquals(0xF800, Ssd1331Minimal.rgb(255, 0, 0));
        assertEquals(0x07E0, Ssd1331Minimal.rgb(0, 255, 0));
        assertEquals(0x001F, Ssd1331Minimal.rgb(0, 0, 255));
        assertEquals(0xFFFF, Ssd1331Minimal.rgb(255, 255, 255));
    }

    @Test
    void drawingHelpers() throws IOException {
        Ssd1331Full d = full(new MockDisplayConnection());
        d.drawLine(0, 0, 4, 4, Ssd1331Minimal.WHITE);
        assertTrue(d.getPixel(0, 0) == 0xFFFF && d.getPixel(2, 2) == 0xFFFF && d.getPixel(4, 4) == 0xFFFF);
        assertEquals(0, d.getPixel(4, 0));
        d.clear(0);
        d.drawRect(2, 2, 4, 3, Ssd1331Minimal.WHITE, false);
        assertTrue(d.getPixel(2, 2) == 0xFFFF && d.getPixel(5, 4) == 0xFFFF);
        assertTrue(d.getPixel(3, 3) == 0 && d.getPixel(6, 2) == 0);
        d.drawRect(10, 10, 3, 3, Ssd1331Minimal.RED, true);
        assertTrue(d.getPixel(11, 11) == Ssd1331Minimal.RED && d.getPixel(13, 13) == 0);
        d.blit(20, 20, new int[] {1, 2, 3, 4}, 2, 2);
        assertEquals(Arrays.asList(1, 2, 3, 4),
                Arrays.asList(d.getPixel(20, 20), d.getPixel(21, 20), d.getPixel(20, 21), d.getPixel(21, 21)));
    }

    @Test
    void showAndShowRegion() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        Ssd1331Full d = full(m);
        d.clear(Ssd1331Minimal.RED);
        int n = m.events().size();
        d.show();
        List<Integer> s = stream(m, n);
        assertEquals(l(0x15, 0, 0x5F, 0x75, 0, 0x3F), s.subList(0, 6));
        assertEquals(-192, (int) s.get(6));
        assertEquals(6 + 64, s.size());
        assertEquals(12288, dataBytes(m, n));
        assertTrue(m.events().get(n + 6).startsWith("data:f8"));
        n = m.events().size();
        d.showRegion(10, 20, 4, 2);
        assertEquals(l(0x15, 10, 13, 0x75, 20, 21, -8, -8), stream(m, n));
        n = m.events().size();
        d.showRegion(90, 60, 20, 20);
        assertEquals(l(0x15, 90, 95, 0x75, 60, 63), stream(m, n).subList(0, 6));
        assertEquals(48, dataBytes(m, n));
        n = m.events().size();
        d.showRegion(200, 0, 5, 5);
        assertEquals(n, m.events().size());
    }

    @Test
    void hardwarePrimitivesAndSettings() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        Ssd1331Full d = full(m);
        int n = m.events().size();
        d.hwLine(1, 2, 3, 4, Ssd1331Minimal.RED);
        assertEquals(l(0x21, 1, 2, 3, 4, 0x3E, 0, 0), stream(m, n));
        n = m.events().size();
        d.hwRect(10, 20, 5, 6, Ssd1331Minimal.GREEN, Ssd1331Minimal.BLUE, true);
        assertEquals(l(0x26, 1, 0x22, 10, 20, 14, 25, 0, 0x3F, 0, 0, 0, 0x3E), stream(m, n));
        n = m.events().size();
        d.hwRect(10, 20, 5, 6, Ssd1331Minimal.WHITE, 0, false);
        assertEquals(l(0x26, 0, 0x22, 10, 20, 14, 25, 0x3E, 0x3F, 0x3E, 0, 0, 0), stream(m, n));
        n = m.events().size();
        d.hwRect(10, 20, 0, 6, Ssd1331Minimal.WHITE, 0, false);
        assertEquals(n, m.events().size());
        n = m.events().size();
        d.hwClear(0, 1, 2, 3);
        d.hwDim(0, 1, 2, 3);
        d.hwCopy(1, 2, 3, 4, 5, 6);
        assertEquals(l(0x25, 0, 1, 2, 3, 0x24, 0, 1, 2, 3, 0x23, 1, 2, 3, 4, 5, 6), stream(m, n));
        n = m.events().size();
        d.scroll(1, 0, 64, 2, 7);
        d.scrollStop();
        assertEquals(l(0x27, 1, 0, 64, 2, 3, 0x2F, 0x2E), stream(m, n));
        n = m.events().size();
        d.setContrast(1, 2, 3);
        d.setMasterCurrent(16);
        d.setMasterCurrent(0);
        d.setDisplayMode(Ssd1331Full.MODE_INVERSE);
        assertEquals(l(0x81, 1, 0x82, 2, 0x83, 3, 0x87, 0x0F, 0x87, 0x00, 0xA7), stream(m, n));
        n = m.events().size();
        d.setFlip(false, false);
        d.setFlip(true, true);
        assertEquals(l(0xA0, 0x60, 0xA0, 0x72), stream(m, n));
        n = m.events().size();
        d.setPowerSave(true);
        d.setPowerSave(false);
        d.dimMode(1, 2, 3, 0xFF);
        d.displayOff();
        d.displayOn();
        d.grayLinear();
        d.sendCommand(0xAA, 0x12, 0x34);
        assertEquals(l(0xB0, 0x1A, 0xB0, 0x0B, 0xAB, 0, 1, 2, 3, 0x1F, 0xAC, 0xAE, 0xAF, 0xB9, 0xAA, 0x12, 0x34),
                stream(m, n));
    }
}
