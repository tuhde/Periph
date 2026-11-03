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

class Uc8276cTest {

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
        new Uc8276cMinimal(m);
        assertEquals("reset", m.events().get(0));
        assertEquals("waitBusy:false:30000", m.events().get(1));
        assertEquals(2, m.commands().size());
        assertCommand(m, 0, 0x00, 0x0F);
        assertCommand(m, 1, 0x50, 0xD7);
    }

    @Test
    void initSendsTresForSmallerPanel() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        new Uc8276cMinimal(m, 200, 100, 0);
        assertCommand(m, 1, 0x61, 0x00, 0xC8, 0x00, 0x64);
        assertEquals("waitBusy:false:0", m.events().get(1));
    }

    @Test
    void invalidSizesAndTimeout() {
        MockDisplayConnection m = new MockDisplayConnection();
        assertThrows(IllegalArgumentException.class, () -> new Uc8276cMinimal(m, 401, 300, 0));
        assertThrows(IllegalArgumentException.class, () -> new Uc8276cMinimal(m, 408, 300, 0));
        assertThrows(IllegalArgumentException.class, () -> new Uc8276cMinimal(m, 400, 301, 0));
        MockDisplayConnection bad = new MockDisplayConnection();
        bad.setReady(false);
        assertThrows(IOException.class, () -> new Uc8276cMinimal(bad));
    }

    @Test
    void framebufferMapping() throws IOException {
        Uc8276cFull d = new Uc8276cFull(new MockDisplayConnection(), 16, 2, 0);
        assertEquals(Uc8276cMinimal.WHITE, d.getPixel(0, 0));
        d.setPixel(0, 0, Uc8276cMinimal.BLACK);
        d.setPixel(9, 1, Uc8276cMinimal.RED);
        assertEquals(Uc8276cMinimal.BLACK, d.getPixel(0, 0));
        assertEquals(Uc8276cMinimal.RED, d.getPixel(9, 1));
        d.setPixel(-1, 0, Uc8276cMinimal.BLACK);
        d.setPixel(16, 0, Uc8276cMinimal.BLACK);
        d.setPixel(0, 2, Uc8276cMinimal.BLACK);
        d.setPixel(0, 0, Uc8276cMinimal.WHITE);
        assertEquals(Uc8276cMinimal.WHITE, d.getPixel(0, 0));
    }

    @Test
    void showSequence() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        Uc8276cMinimal d = new Uc8276cMinimal(m, 16, 2, 1000);
        d.setPixel(0, 0, Uc8276cMinimal.RED);
        int n = m.events().size();
        d.show();
        List<String> tail = m.events().subList(n, m.events().size());
        assertEquals("command:10:", tail.get(0));
        assertEquals("data:7fffffff", tail.get(1));
        assertEquals("command:13:", tail.get(2));
        assertEquals("data:80000000", tail.get(3));
        assertEquals("command:04:", tail.get(4));
        assertEquals("waitBusy:false:1000", tail.get(5));
        assertEquals("command:12:", tail.get(6));
        assertEquals("command:02:", tail.get(8));
        assertEquals(10, tail.size());
    }

    @Test
    void showWithoutRedSendsZerosAndTimeoutThrows() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        Uc8276cMinimal d = new Uc8276cMinimal(m, 16, 2, 0);
        int n = m.events().size();
        d.show();
        assertEquals("data:00000000", m.events().get(n + 3));
        m.setReady(false);
        assertThrows(IOException.class, d::show);
    }

    @Test
    void sleepThenShowWakes() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        Uc8276cMinimal d = new Uc8276cMinimal(m, 16, 2, 0);
        d.sleep();
        assertCommand(m, m.commands().size() - 1, 0x07, 0xA5);
        d.show();
        assertEquals(2, m.events().stream().filter("reset"::equals).count());
    }

    @Test
    void settingsEncodings() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        Uc8276cFull d = new Uc8276cFull(m, 16, 2, 0);
        int base = m.commands().size();
        d.setMode(Uc8276cMinimal.MODE_KW);
        d.setFlip(true, false);
        d.setPower(10, 10, 10, 16);
        d.setPower(2.4, 11, 5, 13);
        d.setVcomDc(-1.0);
        d.setFrameRate(50);
        d.setFrameRate(200);
        d.setFrameRate(30);
        d.setSoftStart(0x17, 0x17, 0x17);
        d.setInterval(7, 3, 1);
        d.setTemperatureSensor(true, -3);
        assertCommand(m, base, 0x00, 0x1F);
        assertCommand(m, base + 1, 0x00, 0x1B);
        assertCommand(m, base + 2, 0x01, 0x03, 0x00, 0x26, 0x26, 0x26);
        assertCommand(m, base + 3, 0x01, 0x03, 0x03, 0x00, 0x2B, 0x0D);
        assertCommand(m, base + 4, 0x82, 0x12);
        assertCommand(m, base + 5, 0x30, 0x3C);
        assertCommand(m, base + 6, 0x30, 0x39);
        assertCommand(m, base + 7, 0x30, 0x09);
        assertCommand(m, base + 8, 0x06, 0x17, 0x17, 0x17);
        assertCommand(m, base + 9, 0x50, 0xD7);
        assertCommand(m, base + 10, 0x41, 0x8D);
        d.writeLut(new byte[56], null, new byte[56], null, null);
        assertEquals(0x20, (int) m.commands().get(base + 11));
        assertEquals(0x22, (int) m.commands().get(base + 12));
        assertCommand(m, base + 13, 0x00, 0x3B);
        d.sendCommand(0xAB, b(1));
        assertCommand(m, base + 14, 0xAB, 0x01);
    }

    @Test
    void drawingPrimitives() throws IOException {
        Uc8276cFull d = new Uc8276cFull(new MockDisplayConnection(), 16, 2, 0);
        d.drawLine(0, 0, 1, 1, Uc8276cMinimal.BLACK);
        assertEquals(Uc8276cMinimal.BLACK, d.getPixel(0, 0));
        assertEquals(Uc8276cMinimal.BLACK, d.getPixel(1, 1));
        d.drawRect(4, 0, 4, 2, Uc8276cMinimal.RED, true);
        assertEquals(Uc8276cMinimal.RED, d.getPixel(4, 0));
        assertEquals(Uc8276cMinimal.RED, d.getPixel(7, 1));
        d.clear(Uc8276cMinimal.WHITE);
        d.drawRect(0, 0, 4, 2, Uc8276cMinimal.BLACK, false);
        assertEquals(Uc8276cMinimal.BLACK, d.getPixel(3, 1));
        assertEquals(Uc8276cMinimal.WHITE, d.getPixel(4, 0));
        d.clear(Uc8276cMinimal.WHITE);
        d.blit(8, 0, b(0xA0), 4, 1, Uc8276cMinimal.BLACK);
        assertEquals(Uc8276cMinimal.BLACK, d.getPixel(8, 0));
        assertEquals(Uc8276cMinimal.WHITE, d.getPixel(9, 0));
        assertEquals(Uc8276cMinimal.BLACK, d.getPixel(10, 0));
        assertEquals(Uc8276cMinimal.WHITE, d.getPixel(11, 0));
        d.clear(Uc8276cMinimal.RED);
        assertEquals(Uc8276cMinimal.RED, d.getPixel(5, 1));
    }

    @Test
    void partialWindow() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        Uc8276cFull d = new Uc8276cFull(m, 32, 4, 0);
        int n = m.events().size();
        d.showRegion(9, 1, 6, 2);
        List<String> tail = m.events().subList(n, m.events().size());
        assertEquals("command:91:", tail.get(0));
        assertEquals("command:90:0008000f0001000200", tail.get(1));
        assertEquals("command:10:", tail.get(2));
        assertEquals("data:ff", tail.get(3));
        assertEquals("data:ff", tail.get(4));
        assertEquals("command:92:", tail.get(tail.size() - 1));
        assertThrows(IllegalArgumentException.class, () -> d.showRegion(0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> d.showRegion(40, 0, 4, 4));
    }

    @Test
    void autoAndWaitBusy() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection();
        Uc8276cFull d = new Uc8276cFull(m, 16, 2, 0);
        d.refreshAuto(false);
        assertCommand(m, m.commands().size() - 1, 0x17, 0xA5);
        d.refreshAuto(true);
        assertCommand(m, m.commands().size() - 1, 0x17, 0xA7);
        assertTrue(d.waitBusy(10));
        assertEquals("waitBusy:false:10", m.events().get(m.events().size() - 1));
    }

    @Test
    void reads() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection(WireMode.HALF_DUPLEX_PIN);
        Uc8276cFull d = new Uc8276cFull(m, 16, 2, 0);
        m.queueRead(b(0x05));
        assertEquals(5, d.readStatus());
        m.queueRead(b(0xE7, 0x00));
        assertEquals(-25, d.readTemperature());
        m.queueRead(b(0x19));
        assertArrayEquals(new int[] {-1, 0x19}, d.readRevision(false));
        m.queueRead(b(0x00, 0x02, 0x01, 0x02, 0x03));
        assertArrayEquals(new int[] {2, 0x030201}, d.readRevision(true));
        m.queueRead(b(0x0A));
        assertEquals(-0.60, d.readVcom(), 1e-9);
        m.queueRead(b(0x00));
        assertTrue(d.readLowPower());
        m.queueRead(b(0xFF, 0x11, 0x22));
        assertArrayEquals(b(0x11, 0x22), d.readOtp(2));

        Uc8276cFull simplex = new Uc8276cFull(new MockDisplayConnection(), 16, 2, 0);
        assertThrows(UnsupportedOperationException.class, simplex::readStatus);
        assertTrue(simplex.waitBusy(1));
    }
}
