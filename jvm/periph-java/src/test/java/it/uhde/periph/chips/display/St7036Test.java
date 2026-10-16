package it.uhde.periph.chips.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.uhde.periph.connection.MockDisplayConnection;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class St7036Test {

    private static final String INIT_162 =
        "R d40 I39 u30 I14 u30 I78 u30 I55 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30";

    /** R reset, I<hh> instruction, D<hh> data byte, d<ms> delay, u<us> microsecond delay. */
    private static String trace(MockDisplayConnection m, int from) {
        StringBuilder sb = new StringBuilder();
        List<String> ev = m.events();
        for (int i = from; i < ev.size(); i++) {
            String e = ev.get(i);
            String t;
            if (e.equals("reset")) t = "R";
            else if (e.startsWith("command:")) {
                String[] p = e.split(":", -1);
                assertEquals("", p[2], "instructions are single bytes");
                t = "I" + p[1].toUpperCase();
            } else if (e.startsWith("data:")) {
                String h = e.substring(5);
                t = "D" + h.substring(0, 2).toUpperCase() + (h.length() != 2 ? "!" : "");
            } else if (e.startsWith("delayUs:")) t = "u" + e.substring(8);
            else if (e.startsWith("delay:")) t = "d" + e.substring(6);
            else t = "?";
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        return sb.toString();
    }

    private static St7036Full full(MockDisplayConnection m, int lines) throws IOException {
        return new St7036Full(m, lines, 0, St7036Full.SUPPLY_3V3, -1);
    }

    @Test
    void initSequenceDogm162() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        St7036Minimal d = new St7036Minimal(m);
        assertEquals(INIT_162, trace(m, 0));
        assertEquals(16, d.columns());
        assertEquals(2, d.rows());
    }

    @Test
    void minimalOperations() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        St7036Minimal d = new St7036Minimal(m);
        int n = m.events().size();
        d.clear();
        assertEquals("I01 d2", trace(m, n));
        n = m.events().size();
        d.home();
        assertEquals("I02 d2", trace(m, n));
        n = m.events().size();
        d.setCursor(3, 1);
        assertEquals("IC3 u30", trace(m, n));
        n = m.events().size();
        d.setCursor(0, 0);
        assertEquals("I80 u30", trace(m, n));
        n = m.events().size();
        d.setCursor(16, 0);
        d.setCursor(0, 2);
        d.setCursor(-1, 0);
        assertEquals(n, m.events().size());
        d.write("Hi");
        assertEquals("D48 u30 D69 u30", trace(m, n));
        n = m.events().size();
        d.write("a\u00e4z~");
        assertEquals("D61 u30 D3F u30 D7A u30 D3F u30", trace(m, n));
        n = m.events().size();
        d.writeChar(0x05);
        assertEquals("D05 u30", trace(m, n));
        n = m.events().size();
        d.printAt(2, 1, "x");
        assertEquals("IC2 u30 D78 u30", trace(m, n));
        n = m.events().size();
        d.write(null);
        assertEquals(n, m.events().size());
    }

    @Test
    void presets() throws IOException {
        Object[][] cases = {
            {1, St7036Full.SUPPLY_3V3, "R d40 I31 u30 I14 u30 I7C u30 I55 u30 I6D u30 d200 I30 u30 I0C u30 I01 d2 I06 u30"},
            {1, St7036Full.SUPPLY_5V, "R d40 I31 u30 I1C u30 I74 u30 I51 u30 I6A u30 d200 I30 u30 I0C u30 I01 d2 I06 u30"},
            {2, St7036Full.SUPPLY_3V3, "R d40 I39 u30 I14 u30 I78 u30 I55 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"},
            {2, St7036Full.SUPPLY_5V, "R d40 I39 u30 I1C u30 I74 u30 I52 u30 I69 u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"},
            {3, St7036Full.SUPPLY_3V3, "R d40 I39 u30 I15 u30 I72 u30 I55 u30 I6E u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"},
            {3, St7036Full.SUPPLY_5V, "R d40 I39 u30 I1D u30 I7C u30 I50 u30 I6C u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"},
        };
        for (Object[] c : cases) {
            MockDisplayConnection m = new MockDisplayConnection();
            new St7036Full(m, (Integer) c[0], 0, (Integer) c[1], -1);
            assertEquals(c[2], trace(m, 0), "lines " + c[0] + " supply " + c[1]);
        }
    }

    @Test
    void geometryAndOverrides() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        St7036Full d = full(m, 3);
        assertEquals(16, d.columns());
        assertEquals(3, d.rows());
        int n = m.events().size();
        d.setCursor(0, 2);
        assertEquals("IA0 u30", trace(m, n));
        St7036Full one = full(new MockDisplayConnection(), 1);
        assertEquals(8, one.columns());
        assertEquals(1, one.rows());
        MockDisplayConnection m2 = new MockDisplayConnection();
        new St7036Full(m2, 2, 0, St7036Full.SUPPLY_3V3, 0x2A);
        assertEquals("R d40 I39 u30 I14 u30 I7A u30 I56 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30", trace(m2, 0));
        assertEquals(20, new St7036Full(new MockDisplayConnection(), 2, 20, St7036Full.SUPPLY_3V3, -1).columns());
        assertThrows(IllegalArgumentException.class, () -> full(new MockDisplayConnection(), 9));
        MockDisplayConnection m3 = new MockDisplayConnection();
        new St7036Full(m3);
        assertEquals(INIT_162, trace(m3, 0));
    }

    @Test
    void controlInstructions() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        St7036Full d = full(m, 2);
        int n = m.events().size();
        d.display(false);
        assertEquals("I08 u30", trace(m, n));
        n = m.events().size();
        d.display(true);
        assertEquals("I0C u30", trace(m, n));
        n = m.events().size();
        d.cursor(true, true);
        assertEquals("I0E u30 I0F u30", trace(m, n));
        n = m.events().size();
        d.cursor(false, false);
        assertEquals("I0D u30 I0C u30", trace(m, n));
        n = m.events().size();
        d.setEntryMode(false, true);
        assertEquals("I05 u30", trace(m, n));
        n = m.events().size();
        d.setEntryMode(true, false);
        assertEquals("I06 u30", trace(m, n));
        n = m.events().size();
        d.shiftCursor(true);
        d.shiftCursor(false);
        assertEquals("I14 u30 I10 u30", trace(m, n));
        n = m.events().size();
        d.shiftDisplay(true);
        d.shiftDisplay(false);
        assertEquals("I1C u30 I18 u30", trace(m, n));
        n = m.events().size();
        d.returnHome();
        assertEquals("I02 d2", trace(m, n));
        n = m.events().size();
        d.setDdramAddress(0x45);
        assertEquals("IC5 u30", trace(m, n));
        n = m.events().size();
        d.sendCommand(0x1F);
        d.sendData(0x41);
        assertEquals("I1F u30 D41 u30", trace(m, n));
    }

    @Test
    void extensionInstructions() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        St7036Full d = full(m, 2);
        int n = m.events().size();
        d.setContrast(0x2D);
        assertEquals("I39 u30 I56 u30 I7D u30 I38 u30", trace(m, n));
        n = m.events().size();
        d.setBooster(false);
        assertEquals("I39 u30 I52 u30 I38 u30", trace(m, n));
        n = m.events().size();
        d.setBooster(true);
        assertEquals("I39 u30 I56 u30 I38 u30", trace(m, n));
        n = m.events().size();
        d.setFollower(true, 3);
        assertEquals("I39 u30 I6B u30 I38 u30", trace(m, n));
        n = m.events().size();
        d.setFollower(false, 7);
        assertEquals("I39 u30 I67 u30 I38 u30", trace(m, n));
        n = m.events().size();
        d.setBias(true);
        assertEquals("I39 u30 I1C u30 I38 u30", trace(m, n));
        n = m.events().size();
        d.setBias(false);
        assertEquals("I39 u30 I14 u30 I38 u30", trace(m, n));
        n = m.events().size();
        d.icon(true);
        assertEquals("I39 u30 I5E u30 I38 u30", trace(m, n));
        n = m.events().size();
        d.writeIcon(3, 0x15);
        assertEquals("I39 u30 I43 u30 D15 u30 I38 u30 I80 u30", trace(m, n));
        MockDisplayConnection m3 = new MockDisplayConnection();
        St7036Full three = full(m3, 3);
        n = m3.events().size();
        three.setBias(false);
        assertEquals("I39 u30 I15 u30 I38 u30", trace(m3, n));
    }

    @Test
    void customCharsAndDoubleHeight() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        St7036Full d = full(m, 2);
        d.setCursor(5, 1);
        int n = m.events().size();
        int[] glyph = {0x1F, 0x00, 0xFF, 0x01, 0x02, 0x04, 0x08, 0x10};
        d.createChar(2, glyph);
        assertEquals("I50 u30 D1F u30 D00 u30 D1F u30 D01 u30 D02 u30 D04 u30 D08 u30 D10 u30 IC5 u30", trace(m, n));
        n = m.events().size();
        d.createChar(9, glyph);
        assertTrue(trace(m, n).startsWith("I48 u30"));
        n = m.events().size();
        d.setDoubleHeight(true, true);
        assertEquals(n, m.events().size());
        MockDisplayConnection m1 = new MockDisplayConnection();
        St7036Full one = full(m1, 1);
        n = m1.events().size();
        one.setDoubleHeight(true, true);
        assertEquals("I34 u30 I34 u30", trace(m1, n));
        n = m1.events().size();
        one.setDoubleHeight(false, true);
        assertEquals("I30 u30 I30 u30", trace(m1, n));
        MockDisplayConnection m3 = new MockDisplayConnection();
        St7036Full three = full(m3, 3);
        n = m3.events().size();
        three.setDoubleHeight(true, true);
        assertEquals("I3E u30 I18 u30 I3C u30", trace(m3, n));
        n = m3.events().size();
        three.setDoubleHeight(true, false);
        assertEquals("I3E u30 I10 u30 I3C u30", trace(m3, n));
    }
}
