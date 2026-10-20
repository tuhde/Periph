package it.uhde.periph.chips.display;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Uc1611sTest {

    private static final byte[] DE = bytes(0xDA, 0x80, 0x10);   // Status1 with DE set

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static byte[] last(MockConnection c) {
        return c.writes().get(c.writes().size() - 1);
    }

    private static MockConnection command() {
        MockConnection c = new MockConnection();
        c.queueRead(DE);   // Status1.DE set: init does not wait for the pump
        return c;
    }

    @Test
    void initSequence() throws Exception {
        MockConnection cmd = command();
        MockConnection dat = new MockConnection();
        Uc1611sMinimal lcd = new Uc1611sMinimal(cmd, dat);
        assertEquals(240, lcd.width());
        assertEquals(128, lcd.height());
        // 1 System Reset + 1 configuration + 20 page addresses + 1 display enable + 1 NOP before the status poll
        assertEquals(24, cmd.writes().size());
        assertArrayEquals(bytes(0xE2), cmd.writes().get(0));
        assertArrayEquals(bytes(0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x8F, 0x89, 0xD1), cmd.writes().get(1));
        assertArrayEquals(bytes(0x60, 0x70, 0x00, 0x10), cmd.writes().get(2));
        assertArrayEquals(bytes(0x63, 0x71, 0x00, 0x10), cmd.writes().get(21));
        assertArrayEquals(bytes(0xA9), cmd.writes().get(22));
        assertArrayEquals(bytes(0xE3), cmd.writes().get(23));
        assertEquals(160, dat.writes().size());
        for (int i = 0; i < dat.writes().size(); i++) {
            byte[] w = dat.writes().get(i);
            assertEquals(i % 8 == 7 ? 16 : 32, w.length);
            assertArrayEquals(new byte[w.length], w);
        }
    }

    @Test
    void invalidSizesAreRejected() {
        for (int[] s : new int[][] {{241, 128}, {240, 130}, {240, 136}, {0, 128}}) {
            MockConnection cmd = command();
            assertThrows(IllegalArgumentException.class, () -> new Uc1611sMinimal(cmd, new MockConnection(), s[0], s[1]));
            assertTrue(cmd.writes().isEmpty());
        }
    }

    @Test
    void pixelsAndShow() throws Exception {
        MockConnection cmd = command();
        MockConnection dat = new MockConnection();
        Uc1611sMinimal lcd = new Uc1611sMinimal(cmd, dat);
        int c0 = cmd.writes().size();
        int d0 = dat.writes().size();
        lcd.setPixel(3, 10, true);
        lcd.setPixel(239, 127, true);
        lcd.setPixel(240, 0, true);   // out of range, ignored
        lcd.setPixel(-1, 5, true);
        assertEquals(c0, cmd.writes().size());
        assertEquals(d0, dat.writes().size());
        lcd.show();
        assertEquals(16, cmd.writes().size() - c0);
        assertEquals(128, dat.writes().size() - d0);
        assertArrayEquals(bytes(0x61, 0x70, 0x00, 0x10), cmd.writes().get(c0 + 1));
        assertEquals(0x04, dat.writes().get(d0 + 8)[3]);
        assertEquals(16, dat.writes().get(d0 + 127).length);
        assertEquals((byte) 0x80, dat.writes().get(d0 + 127)[15]);
        lcd.clear();
        d0 = dat.writes().size();
        lcd.show();
        for (byte[] w : dat.writes().subList(d0, dat.writes().size())) assertArrayEquals(new byte[w.length], w);
    }

    @Test
    void commands() throws Exception {
        MockConnection cmd = command();
        Uc1611sFull c = new Uc1611sFull(cmd, new MockConnection());
        c.setContrast(0x55);       assertArrayEquals(bytes(0x81, 0x55), last(cmd));
        c.setDisplay(false);       assertArrayEquals(bytes(0xA8), last(cmd));
        c.sleep();                 assertArrayEquals(bytes(0xA8), last(cmd));
        c.setInvert(true);         assertArrayEquals(bytes(0xA7), last(cmd));
        c.setInvert(false);        assertArrayEquals(bytes(0xA6), last(cmd));
        c.setAllOn(true);          assertArrayEquals(bytes(0xA5), last(cmd));
        c.setAllOn(false);         assertArrayEquals(bytes(0xA4), last(cmd));
        c.setFlip(true, true);     assertArrayEquals(bytes(0xC0, 0x06), last(cmd));
        c.setFlip(false, true);    assertArrayEquals(bytes(0xC0, 0x04), last(cmd));
        c.setScrollLine(40);       assertArrayEquals(bytes(0x48, 0x52), last(cmd));
        c.setFixedLines(3);        assertArrayEquals(bytes(0x93), last(cmd));
        c.setBiasRatio(3);         assertArrayEquals(bytes(0xEB), last(cmd));
        c.setTemperatureCompensation(2); assertArrayEquals(bytes(0x26), last(cmd));
        c.setLineRate(1);          assertArrayEquals(bytes(0xA1), last(cmd));
        c.setPump(false);          assertArrayEquals(bytes(0x2C), last(cmd));
        c.setPump(true);           assertArrayEquals(bytes(0x2F), last(cmd));
        c.setPanelLoading(true);   assertArrayEquals(bytes(0x2B), last(cmd));
        c.setPanelLoading(false);  assertArrayEquals(bytes(0x28), last(cmd));
        c.setPartialDisplay(true, 10, 100); assertArrayEquals(bytes(0xF2, 10, 0xF3, 100, 0x87), last(cmd));
        c.setPartialDisplay(true, 4, -1);   assertArrayEquals(bytes(0xF2, 4, 0xF3, 127, 0x87), last(cmd));
        c.setPartialDisplay(false, 0, -1);  assertArrayEquals(bytes(0x84), last(cmd));
        c.setNLineInversion(5, true);       assertArrayEquals(bytes(0xC8, 0x45), last(cmd));
        int k = cmd.writes().size();
        c.setIsolationClock(2, 3);
        assertEquals(2, cmd.writes().size() - k);
        assertArrayEquals(bytes(0x82, 0x13, 0x02), cmd.writes().get(k));
        assertArrayEquals(bytes(0x82, 0x14, 0x03), cmd.writes().get(k + 1));
        c.setDisplayPattern(2);    assertArrayEquals(bytes(0xD5), last(cmd));
        c.setDisplayPattern(0);    assertArrayEquals(bytes(0xD1), last(cmd));
        c.sendCommand(0xE3, 0xE3); assertArrayEquals(bytes(0xE3, 0xE3), last(cmd));
    }

    @Test
    void writePatternWakeResetAndRawData() throws Exception {
        MockConnection cmd = command();
        MockConnection dat = new MockConnection();
        Uc1611sFull c = new Uc1611sFull(cmd, dat);
        int d0 = dat.writes().size();
        c.setWritePattern(1);
        c.showPage(0);
        assertArrayEquals(bytes(0x60, 0x72, 0x00, 0x10), last(cmd));
        assertEquals(8, dat.writes().size() - d0);
        c.setWritePattern(0);
        int n = dat.writes().size();
        c.showPage(16);
        assertEquals(n, dat.writes().size());
        n = dat.writes().size();
        c.sendData(new byte[70]);
        assertEquals(3, dat.writes().size() - n);
        assertEquals(6, last(dat).length);

        cmd.queueRead(DE);
        c.sleep();
        c.wake();
        assertArrayEquals(bytes(0xA9), cmd.writes().get(cmd.writes().size() - 2));
        assertArrayEquals(bytes(0xE3), last(cmd));

        c.setContrast(0x55);
        c.setFlip(false, true);
        cmd.queueRead(DE);
        int k = cmd.writes().size();
        c.reset();
        assertEquals(4, cmd.writes().size() - k);
        assertArrayEquals(bytes(0xE2), cmd.writes().get(k));
        assertArrayEquals(bytes(0x25, 0xC0, 0x04, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x55, 0x89, 0xD1), cmd.writes().get(k + 1));
        assertArrayEquals(bytes(0xA9), cmd.writes().get(k + 2));
    }

    @Test
    void drawing() throws Exception {
        Uc1611sFull c = new Uc1611sFull(command(), new MockConnection());
        c.fill(true);
        assertTrue(c.getPixel(0, 0) && c.getPixel(239, 127));
        c.fill(false);
        assertFalse(c.getPixel(0, 0) || c.getPixel(239, 127) || c.getPixel(-1, 0) || c.getPixel(0, 128));
        c.drawLine(0, 0, 9, 9, true);
        assertTrue(c.getPixel(0, 0) && c.getPixel(5, 5) && c.getPixel(9, 9) && !c.getPixel(5, 6));
        c.fill(false);
        c.drawRect(10, 20, 5, 4, true, false);
        assertTrue(c.getPixel(10, 20) && c.getPixel(14, 23) && c.getPixel(12, 20) && !c.getPixel(12, 21));
        c.fill(false);
        c.drawRect(10, 20, 3, 3, true, true);
        assertTrue(c.getPixel(11, 21) && c.getPixel(12, 22) && !c.getPixel(13, 22));
        c.fill(false);
        c.blit(4, 8, bytes(0xFF, 0x0F, 0x01, 0x80), 2);
        assertTrue(c.getPixel(4, 8) && c.getPixel(4, 15) && c.getPixel(5, 8) && !c.getPixel(5, 12)
                && c.getPixel(5, 11) && c.getPixel(4, 16) && c.getPixel(5, 23) && !c.getPixel(4, 17));
        assertThrows(IllegalArgumentException.class, () -> c.blit(4, 3, bytes(1, 2), 1));
    }

    @Test
    void topViewAndCustomSize() throws Exception {
        MockConnection cmd = command();
        new Uc1611sFull(cmd, new MockConnection(), 240, 128, true, 0x60);
        assertArrayEquals(bytes(0x25, 0xC0, 0x04, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x60, 0x89, 0xD1), cmd.writes().get(1));
        MockConnection cmd2 = command();
        new Uc1611sMinimal(cmd2, new MockConnection(), 100, 64);
        assertArrayEquals(bytes(0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x3F, 0xF2, 0x00, 0xF3, 0x3F, 0x81, 0x8F, 0x89, 0xD1), cmd2.writes().get(1));
    }

    @Test
    void readsDiscardTheDummyByteAndStatusSendsNopFirst() throws Exception {
        MockConnection cmd = command();
        MockConnection dat = new MockConnection();
        Uc1611sFull c = new Uc1611sFull(cmd, dat);
        cmd.queueRead(bytes(0xDA, 0x80, 0x10));
        assertArrayEquals(bytes(0xDA, 0x80, 0x10), c.readStatus());
        assertArrayEquals(bytes(0xE3), last(cmd));
        dat.queueRead(bytes(0xEE, 0x11, 0x22, 0x33));
        int k = cmd.writes().size();
        assertArrayEquals(bytes(0x11, 0x22, 0x33), c.readRam(2, 5, 3));
        assertArrayEquals(bytes(0x62, 0x70, 0x05, 0x10), cmd.writes().get(k));
    }

    // ---- error handling ----------------------------------------------------------------

    @Test
    void commandIsRetriedUntilAcknowledgedAndGivesUpAfterMaxAttempts() throws Exception {
        MockConnection cmd = command();
        Uc1611sFull c = new Uc1611sFull(cmd, new MockConnection());
        assertTrue(c.ok());
        int e0 = cmd.writes().size();
        cmd.failNextWrites(2, 0);
        c.setContrast(0x66);
        assertEquals(3, cmd.writes().size() - e0);
        assertArrayEquals(bytes(0x81, 0x66), cmd.writes().get(e0));
        assertEquals(2, c.retryCount());
        assertTrue(c.ok());

        cmd.failNextWrites(100, 0);
        e0 = cmd.writes().size();
        assertThrows(IOException.class, () -> c.setInvert(true));
        assertEquals(Uc1611sMinimal.MAX_ATTEMPTS, cmd.writes().size() - e0);
        assertFalse(c.ok());
        assertEquals(1, c.errorCount());
        assertEquals(2 + Uc1611sMinimal.MAX_ATTEMPTS, c.retryCount());
        cmd.failNextWrites(0, 0);
        c.clearErrors();
        assertTrue(c.ok());
        assertEquals(0, c.retryCount());
    }

    @Test
    void aFailedDataChunkRepeatsTheWholePage() throws Exception {
        MockConnection cmd = command();
        MockConnection dat = new MockConnection();
        Uc1611sFull c = new Uc1611sFull(cmd, dat);
        int e0 = cmd.writes().size();
        int d0 = dat.writes().size();
        dat.failNextWrites(1, 0);
        c.showPage(1);
        assertEquals(2, cmd.writes().size() - e0);
        assertArrayEquals(bytes(0x61, 0x70, 0x00, 0x10), cmd.writes().get(e0));
        assertArrayEquals(bytes(0x61, 0x70, 0x00, 0x10), cmd.writes().get(e0 + 1));
        assertEquals(1 + 8, dat.writes().size() - d0);
        assertEquals(1, c.retryCount());
        e0 = cmd.writes().size();
        d0 = dat.writes().size();
        dat.failNextWrites(1, 2);   // third chunk fails
        c.showPage(2);
        assertEquals(2, cmd.writes().size() - e0);
        assertEquals(3 + 8, dat.writes().size() - d0);
        assertTrue(c.ok());
    }

    @Test
    void deadChipThrowsFromTheConstructor() {
        MockConnection cmd = new MockConnection();
        cmd.failNextWrites(1000, 0);
        MockConnection dat = new MockConnection();
        assertThrows(IOException.class, () -> new Uc1611sMinimal(cmd, dat));
        assertEquals(Uc1611sMinimal.MAX_ATTEMPTS, cmd.writes().size());
        assertTrue(dat.writes().isEmpty());
    }

    @Test
    void readsAreRetried() throws Exception {
        MockConnection cmd = command();
        MockConnection dat = new MockConnection();
        Uc1611sFull c = new Uc1611sFull(cmd, dat);
        cmd.failNextReads(2);
        cmd.queueRead(bytes(0xDA, 0x80, 0x10));
        assertArrayEquals(bytes(0xDA, 0x80, 0x10), c.readStatus());
        assertTrue(c.retryCount() >= 2);
        cmd.failNextReads(100);
        assertThrows(IOException.class, c::readStatus);
        assertFalse(c.ok());
        c.clearErrors();
        cmd.failNextReads(0);
        dat.failNextReads(1);
        dat.queueRead(bytes(0xEE, 0x0A, 0x0B));
        int k = cmd.writes().size();
        assertArrayEquals(bytes(0x0A, 0x0B), c.readRam(1, 4, 2));
        assertEquals(2, cmd.writes().size() - k);
    }
}
