package it.uhde.periph.chips.other;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PCA9541Test {

    /** Recomputes the read-only N bits of CONTROL after a write (this master's view). */
    static class SimMock extends MockConnection {
        @Override
        public void write(byte[] data) throws IOException {
            int prior = registers().getOrDefault(0x01, 0);
            super.write(data);
            if (data.length == 2 && data[0] == 0x01) {
                int w = data[1] & 0xFF;
                int mybus = w & 1, buson = (w >> 2) & 1;
                int nmybusPrior = (prior >> 1) & 1, nbusonPrior = (prior >> 3) & 1;
                boolean control = mybus == nmybusPrior;
                boolean on = buson != nbusonPrior;
                int nmybus = control ? mybus : 1 - mybus;
                int nbuson = on ? 1 - buson : buson;
                setRegister(0x01, (w & 0xF5) | (nmybus << 1) | (nbuson << 3));
            }
        }
    }

    private static MockConnection mock(int control, int ie, int istat) {
        MockConnection c = new SimMock();
        c.setRegister(0x00, ie);
        c.setRegister(0x01, control);
        c.setRegister(0x02, istat);
        c.setRegister(0x10, ie);
        c.setRegister(0x11, control);
        c.setRegister(0x12, istat);
        return c;
    }

    private static int reg(MockConnection c, int r) { return c.registers().get(r); }

    private static List<byte[]> dataWrites(MockConnection c) {
        List<byte[]> out = new ArrayList<>();
        for (byte[] w : c.writes()) if (w.length > 1) out.add(w);
        return out;
    }

    private static int lastWrite(MockConnection c) {
        List<byte[]> dw = dataWrites(c);
        return dw.get(dw.size() - 1)[1] & 0xFF;
    }

    @Test
    void initReadsAndWritesNothing() throws Exception {
        MockConnection c = mock(0x04, 0, 0);
        new PCA9541Full(c);
        assertEquals(0, dataWrites(c).size());
        assertEquals(2, c.writes().size());
        assertEquals(0x01, c.writes().get(0)[0]);
        assertEquals(0x02, c.writes().get(1)[0]);

        MockConnection bad = mock(0, 0, 0);
        bad.failNextWrites(1, 0);
        assertThrows(IOException.class, () -> new PCA9541Minimal(bad));
    }

    @Test
    void acquireFollowsTable12() throws Exception {
        int[][] table = {{0x0, 0x4}, {0x1, 0x4}, {0x2, 0x5}, {0x3, 0x5}, {0x5, 0x4}, {0x6, 0x5},
                         {0x9, 0x0}, {0xA, 0x1}, {0xC, 0x0}, {0xD, 0x0}, {0xE, 0x1}, {0xF, 0x1}};
        for (int[] row : table) {
            MockConnection c = mock(row[0], 0, 0);
            new PCA9541Full(c).acquire(false, 0);
            assertEquals(row[1], lastWrite(c), "CONTROL read " + row[0]);
        }
        for (int read : new int[]{0x4, 0x7, 0x8, 0xB}) {
            assertTrue(new PCA9541Full(mock(read, 0, 0)).acquire(false, 0), "keeps control " + read);
        }
    }

    @Test
    void hasControlAndBusInit() throws Exception {
        int[][] cases = {{0x04, 1}, {0x0A, 0}, {0x00, 0}, {0x02, 0}, {0x07, 1}, {0x05, 0}};
        for (int[] cs : cases) {
            assertEquals(cs[1] == 1, new PCA9541Full(mock(cs[0], 0, 0)).hasControl(), "control " + cs[0]);
        }
        MockConnection c = mock(0x0A, 0, 0x02);
        PCA9541Full chip = new PCA9541Full(c);
        assertTrue(chip.acquire(true, 20));
        assertEquals(0x11, lastWrite(c));
        assertFalse(new PCA9541Full(mock(0x0A, 0, 0x00)).acquire(true, 3));
        MockConnection r = mock(0x0A, 0, 0x02);
        assertTrue(new PCA9541Full(r).recoverBus(20));
        assertTrue((lastWrite(r) & 0x10) != 0);
    }

    @Test
    void releaseConnectDisconnect() throws Exception {
        MockConnection c = mock(0x04, 0, 0);
        PCA9541Full chip = new PCA9541Full(c);
        chip.release();
        assertEquals(0x05, lastWrite(c));
        assertFalse(chip.hasControl());

        MockConnection c2 = mock(0x0D, 0, 0);
        new PCA9541Full(c2).connect();
        assertEquals(0x01, lastWrite(c2));

        MockConnection c3 = mock(0x04, 0, 0);
        PCA9541Full chip3 = new PCA9541Full(c3);
        chip3.disconnect();
        assertEquals(0x00, lastWrite(c3));
        assertFalse(chip3.isConnected());
        chip3.connect();
        assertTrue(chip3.isConnected());
    }

    @Test
    void statusAndReadAll() throws Exception {
        PCA9541Full chip = new PCA9541Full(mock(0xD4, 0, 0));
        PCA9541Full.Status st = chip.status();
        assertEquals(0xD4, chip.getControl());
        assertTrue(st.hasControl && st.connected && st.busInitRequested && st.ownTest && st.otherTest);

        MockConnection c = mock(0x04, 0x03, 0x08);
        PCA9541Full chip2 = new PCA9541Full(c);
        int n = c.writes().size();
        assertArrayEquals(new int[]{0x03, 0x04, 0x08}, chip2.readAll());
        assertEquals(n + 1, c.writes().size());
        assertEquals(0x10, c.writes().get(c.writes().size() - 1)[0]);
    }

    @Test
    void interruptMaskAndTest() throws Exception {
        MockConnection c = mock(0x0D, 0, 0);
        PCA9541Full chip = new PCA9541Full(c);
        chip.setInterruptMask(true, false, false, true);
        assertEquals(0x09, chip.getInterruptMask());
        chip.setInterruptMask(false, false, false, false);
        chip.disableInterrupt(PCA9541Full.SOURCE_BUS_INIT);
        assertEquals(0x02, reg(c, 0));
        chip.disableInterrupt(PCA9541Full.SOURCE_INT_IN);
        chip.enableInterrupt(PCA9541Full.SOURCE_BUS_INIT);
        assertEquals(0x01, reg(c, 0));
        int n = dataWrites(c).size();
        chip.enableInterrupt(PCA9541Full.SOURCE_TEST);
        assertEquals(n, dataWrites(c).size());

        int bits = reg(c, 1) & 0x05;
        chip.testInterrupt(true, false);
        assertEquals(bits | 0x40, reg(c, 1) & 0xC5);
        chip.testInterrupt(false, true);
        assertEquals(0x80, reg(c, 1) & 0xC0);
        chip.testInterrupt(true, true);
        assertEquals(0xC0, reg(c, 1) & 0xC0);
        chip.testInterrupt(false, false);
        assertEquals(0, reg(c, 1) & 0xC0);
        assertEquals(bits, reg(c, 1) & 0x05);
    }

    @Test
    void pollAndCallback() throws Exception {
        MockConnection c = mock(0x04, 0, 0x0A);
        PCA9541Full chip = new PCA9541Full(c);
        assertEquals(0x0A, chip.pollInterrupt());

        AtomicInteger calls = new AtomicInteger();
        AtomicInteger last = new AtomicInteger();
        chip.onInterrupt(s -> { calls.incrementAndGet(); last.set(s); });
        long end = System.currentTimeMillis() + 2000;
        while (calls.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10);
        c.setRegister(0x02, 0x00);
        chip.offInterrupt();
        assertTrue(calls.get() >= 1);
        assertEquals(0x0A, last.get());
    }
}
