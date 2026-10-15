package it.uhde.periph.chips.display;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.uhde.periph.connection.MockDisplayConnection;
import it.uhde.periph.connection.WireMode;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class Il0373Test {

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    private static void assertCommand(MockDisplayConnection m, int index, int cmd, int... data) {
        assertEquals(cmd, (int) m.commands().get(index));
        assertArrayEquals(b(data), m.commandData(index));
    }

    @Test
    void initSequenceDefaultSize() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        Il0373Minimal d = new Il0373Minimal(m);
        assertEquals("reset", m.events().get(0));
        assertEquals("waitBusy:false:30000", m.events().get(1));
        assertEquals(5, m.commands().size());
        assertCommand(m, 0, 0x06, 0x17, 0x17, 0x17);
        assertCommand(m, 1, 0x04);
        assertCommand(m, 2, 0x00, 0x1F);
        assertCommand(m, 3, 0x61, 0x68, 0x00, 0xD4);
        assertCommand(m, 4, 0x50, 0x97);
        assertEquals("waitBusy:false:30000", m.events().get(4));
        assertEquals(104, d.width());
        assertEquals(212, d.height());
    }

    @Test
    void initTresForLargestPanel() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        new Il0373Minimal(m, 160, 296, 0);
        assertCommand(m, 3, 0x61, 0xA0, 0x01, 0x28);
        assertEquals("waitBusy:false:0", m.events().get(1));
    }

    @Test
    void invalidSizesAndTimeout() {
        MockDisplayConnection m = new MockDisplayConnection();
        assertThrows(IllegalArgumentException.class, () -> new Il0373Minimal(m, 101, 212, 0));
        assertThrows(IllegalArgumentException.class, () -> new Il0373Minimal(m, 168, 212, 0));
        assertThrows(IllegalArgumentException.class, () -> new Il0373Minimal(m, 104, 297, 0));
        MockDisplayConnection bad = new MockDisplayConnection();
        bad.setReady(false);
        assertThrows(IOException.class, () -> new Il0373Minimal(bad));
    }

    @Test
    void framebufferMapping() throws IOException {
        Il0373Full d = new Il0373Full(new MockDisplayConnection(), 16, 2, 0);
        assertEquals(Il0373Minimal.WHITE, d.getPixel(0, 0));
        d.setPixel(0, 0, Il0373Minimal.BLACK);
        d.setPixel(9, 1, Il0373Minimal.BLACK);
        assertEquals(Il0373Minimal.BLACK, d.getPixel(0, 0));
        assertEquals(Il0373Minimal.BLACK, d.getPixel(9, 1));
        d.setPixel(-1, 0, Il0373Minimal.BLACK);
        d.setPixel(16, 0, Il0373Minimal.BLACK);
        d.setPixel(0, 2, Il0373Minimal.BLACK);
        d.setPixel(0, 0, Il0373Minimal.WHITE);
        assertEquals(Il0373Minimal.WHITE, d.getPixel(0, 0));
    }

    @Test
    void showSequence() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        Il0373Minimal d = new Il0373Minimal(m, 16, 2, 1000);
        d.setPixel(0, 0, Il0373Minimal.BLACK);
        int n = m.events().size();
        d.show();
        List<String> tail = m.events().subList(n, m.events().size());
        assertEquals("command:10:", tail.get(0));
        assertEquals("data:80000000", tail.get(1));
        assertEquals("command:13:", tail.get(2));
        assertEquals("data:7fffffff", tail.get(3));
        assertEquals("command:12:", tail.get(4));
        assertEquals("waitBusy:false:1000", tail.get(5));
        assertEquals("command:50:f7", tail.get(6));
        assertEquals("command:02:", tail.get(7));
        assertEquals(9, tail.size());
        // the booster is off now: the second show powers on first
        n = m.events().size();
        d.show();
        tail = m.events().subList(n, m.events().size());
        assertEquals("command:04:", tail.get(0));
        assertEquals("waitBusy:false:1000", tail.get(1));
        assertEquals("command:50:97", tail.get(2));
        assertEquals("command:10:", tail.get(3));
    }

    @Test
    void timeoutThrows() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        Il0373Minimal d = new Il0373Minimal(m, 16, 2, 0);
        m.setReady(false);
        assertThrows(IOException.class, d::show);
    }

    @Test
    void sleepThenShowWakes() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        Il0373Minimal d = new Il0373Minimal(m, 16, 2, 0);
        d.sleep();
        int l = m.commands().size();
        assertCommand(m, l - 3, 0x50, 0xF7);
        assertCommand(m, l - 2, 0x02);
        assertCommand(m, l - 1, 0x07, 0xA5);
        d.show();
        assertEquals(2, m.events().stream().filter("reset"::equals).count());
    }

    @Test
    void settingsEncodings() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        Il0373Full d = new Il0373Full(m, 16, 2, 0);
        int base = m.commands().size();
        d.setFlip(true, false);
        d.setPower(14, 14, 20);
        d.setPower(6.4, 15, 17);
        d.setVcomDc(-1.0);
        d.setVcomDc(-9.0);
        d.setFrameRate(50);
        d.setFrameRate(200);
        d.setFrameRate(30);
        d.setSoftStart(0x17, 0x17, 0x17);
        d.setInterval(7, 2, 1);
        d.setTemperatureSensor(true, -3);
        assertCommand(m, base, 0x00, 0x1B);
        assertCommand(m, base + 1, 0x01, 0x03, 0x00, 0x26, 0x26, 0x03);
        assertCommand(m, base + 2, 0x01, 0x03, 0x03, 0x00, 0x2B, 0x03);
        assertCommand(m, base + 3, 0x82, 0x12);
        assertCommand(m, base + 4, 0x82, 0x3A);
        assertCommand(m, base + 5, 0x30, 0x3C);
        assertCommand(m, base + 6, 0x30, 0x39);
        assertCommand(m, base + 7, 0x30, 0x09);
        assertCommand(m, base + 8, 0x06, 0x17, 0x17, 0x17);
        assertCommand(m, base + 9, 0x50, 0x97);
        assertCommand(m, base + 10, 0x41, 0x8D);
        d.writeLut(0, new byte[44]);
        d.writeLut(4, new byte[42]);
        assertEquals(0x20, (int) m.commands().get(base + 11));
        assertEquals(44, m.commandData(base + 11).length);
        assertCommand(m, base + 12, 0x00, 0x3B);
        assertEquals(0x24, (int) m.commands().get(base + 13));
        assertThrows(IllegalArgumentException.class, () -> d.writeLut(5, new byte[0]));
        d.sendCommand(0xAB, b(1));
        assertCommand(m, base + 15, 0xAB, 0x01);
    }

    @Test
    void drawingPrimitives() throws IOException {
        Il0373Full d = new Il0373Full(new MockDisplayConnection(), 16, 2, 0);
        d.drawLine(0, 0, 1, 1, Il0373Minimal.BLACK);
        assertEquals(Il0373Minimal.BLACK, d.getPixel(0, 0));
        assertEquals(Il0373Minimal.BLACK, d.getPixel(1, 1));
        d.drawRect(4, 0, 4, 2, Il0373Minimal.BLACK, true);
        assertEquals(Il0373Minimal.BLACK, d.getPixel(4, 0));
        assertEquals(Il0373Minimal.BLACK, d.getPixel(7, 1));
        d.clear(Il0373Minimal.WHITE);
        d.drawRect(0, 0, 4, 2, Il0373Minimal.BLACK, false);
        assertEquals(Il0373Minimal.BLACK, d.getPixel(3, 1));
        assertEquals(Il0373Minimal.WHITE, d.getPixel(4, 0));
        d.clear(Il0373Minimal.WHITE);
        d.blit(8, 0, b(0xA0), 4, 1, Il0373Minimal.BLACK);
        assertEquals(Il0373Minimal.BLACK, d.getPixel(8, 0));
        assertEquals(Il0373Minimal.WHITE, d.getPixel(9, 0));
        assertEquals(Il0373Minimal.BLACK, d.getPixel(10, 0));
        assertEquals(Il0373Minimal.WHITE, d.getPixel(11, 0));
        d.clear(Il0373Minimal.BLACK);
        assertEquals(Il0373Minimal.BLACK, d.getPixel(5, 1));
    }

    @Test
    void partialWindow() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        Il0373Full d = new Il0373Full(m, 32, 4, 0);
        int n = m.events().size();
        d.showRegion(9, 1, 6, 2);
        List<String> tail = m.events().subList(n, m.events().size());
        assertEquals("command:91:", tail.get(0));
        assertEquals("command:90:080f00010002" + "00", tail.get(1));
        assertEquals("command:10:", tail.get(2));
        assertEquals("data:00", tail.get(3));
        assertEquals("data:00", tail.get(4));
        assertEquals("command:13:", tail.get(5));
        assertEquals("data:ff", tail.get(6));
        assertEquals("command:92:", tail.get(tail.size() - 1));
        assertThrows(IllegalArgumentException.class, () -> d.showRegion(0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> d.showRegion(40, 0, 4, 4));
        assertTrue(d.waitBusy(10));
        assertEquals("waitBusy:false:10", m.events().get(m.events().size() - 1));
    }

    @Test
    void reads() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection(WireMode.HALF_DUPLEX_PIN);
        Il0373Full d = new Il0373Full(m, 16, 2, 0);
        m.queueRead(b(0x05));
        assertEquals(5, d.readStatus());
        m.queueRead(b(0xE7, 0x00));
        assertEquals(-25, d.readTemperature());
        m.queueRead(b(0x19));
        assertEquals(0x19, d.readRevision());
        m.queueRead(b(0x0A));
        assertEquals(-0.60, d.readVcom(), 1e-9);
        m.queueRead(b(0x00));
        assertTrue(d.readLowPower());
        m.queueRead(b(0xFF, 0x11, 0x22));
        assertArrayEquals(b(0x11, 0x22), d.readOtp(2));

        Il0373Full simplex = new Il0373Full(new MockDisplayConnection(), 16, 2, 0);
        assertThrows(UnsupportedOperationException.class, simplex::readStatus);
    }
}
