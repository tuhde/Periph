package it.uhde.periph.chips.light;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MAX44009Test {

    @BeforeAll
    static void noInitWait() {
        MAX44009Minimal.initWaitMs = 0;   // skip the 900 ms first-conversion wait
    }

    private static MockConnection mock(int high, int low) {
        MockConnection c = new MockConnection();
        c.setRegister(0x02, 0x03);
        c.setRegister(0x03, high);
        c.setRegister(0x04, low);
        return c;
    }

    private static int reg(MockConnection c, int r) { return c.registers().get(r); }

    @Test
    void initSequence() throws Exception {
        MockConnection c = mock(0, 0);
        new MAX44009Full(c);
        List<int[]> seq = new ArrayList<>();
        boolean readStatus = false;
        for (byte[] w : c.writes()) {
            if (w.length > 1) seq.add(new int[]{w[0] & 0xFF, w[1] & 0xFF});
            else if (w.length == 1 && w[0] == 0x00) readStatus = true;
        }
        int[][] want = {{1, 0}, {2, 3}, {5, 255}, {6, 0}, {7, 255}};
        assertEquals(want.length, seq.size());
        for (int i = 0; i < want.length; i++) {
            assertEquals(want[i][0], seq.get(i)[0]);
            assertEquals(want[i][1], seq.get(i)[1]);
        }
        assertTrue(readStatus, "init reads INT_STATUS");
    }

    @Test
    void luxConversion() throws Exception {
        MockConnection c = mock(0, 0);
        MAX44009Full chip = new MAX44009Full(c);
        assertEquals(0.0, chip.lux(), 0.0);
        c.setRegister(0x03, 0x2A);
        c.setRegister(0x04, 0x05);
        assertEquals(4 * 0xA5 * 0.045, chip.lux(), 1e-9);
        c.setRegister(0x03, 0xEF);
        c.setRegister(0x04, 0x0F);
        assertEquals(188006.4, chip.lux(), 1e-6);
        c.setRegister(0x03, 0xF3);
        assertEquals(188006.4, chip.lux(), 1e-6);
        c.setRegister(0x03, 0x01);
        c.setRegister(0x04, 0xF7);
        assertEquals(0x17 * 0.045, chip.lux(), 1e-9);
    }

    @Test
    void rawAndResolution() throws Exception {
        MockConnection c = mock(0x5B, 0x0C);
        MAX44009Full chip = new MAX44009Full(c);
        MAX44009Full.Raw raw = chip.readRaw();
        assertEquals(5, raw.exponent);
        assertEquals(0xBC, raw.mantissa);
        assertFalse(raw.overrange);
        assertEquals(32 * 0.045, chip.resolution(), 1e-9);
        assertFalse(chip.isOverrange());
        c.setRegister(0x03, 0xF0);
        assertTrue(chip.readRaw().overrange);
        assertTrue(chip.isOverrange());
    }

    /** LUX_HIGH changes between the first and second read (a new conversion arrives). */
    @Test
    void consistentReadRepeats() throws Exception {
        MockConnection c = new MockConnection() {
            int highReads = 0;

            @Override
            public byte[] writeRead(byte[] data, int n) throws IOException {
                if ((data[0] & 0xFF) == 0x03) {
                    highReads++;
                    if (highReads == 2) {   // the second LUX_HIGH read sees a new conversion
                        setRegister(0x03, 0x20);
                        setRegister(0x04, 0x02);
                    }
                }
                return super.writeRead(data, n);
            }
        };
        c.setRegister(0x02, 0x03);
        c.setRegister(0x03, 0x10);
        c.setRegister(0x04, 0x01);
        MAX44009Minimal chip = new MAX44009Minimal(c);
        // init never reads LUX_HIGH; lux() reads high (0x10), low, high (now 0x20) and repeats low.
        assertEquals(4 * 0x02 * 0.045, chip.lux(), 1e-9);
    }

    @Test
    void configuration() throws Exception {
        MockConnection c = mock(0, 0);
        MAX44009Full chip = new MAX44009Full(c);
        chip.setContinuous(true);
        assertEquals(0x83, reg(c, 2));
        chip.setContinuous(false);
        assertEquals(0x03, reg(c, 2));
        chip.setManual(50, false);
        assertEquals(0x44, reg(c, 2));
        chip.setManual(12, true);
        assertEquals(0x40 | 0x08 | 0x06, reg(c, 2));
        chip.setContinuous(true);
        chip.setManual(1000, false);
        assertEquals(0xC0, reg(c, 2));
        chip.setAutomatic();
        assertEquals(0, reg(c, 2) & 0x40);
        assertTrue((reg(c, 2) & 0x80) != 0);
        c.setRegister(0x02, 0xCD);
        MAX44009Full.Config cfg = chip.getConfig();
        assertTrue(cfg.continuous && cfg.manual && cfg.divideBy8);
        assertEquals(25.0, cfg.integrationMs, 0.0);
    }

    @Test
    void thresholdsAndDelay() throws Exception {
        MockConnection c = mock(0, 0);
        MAX44009Full chip = new MAX44009Full(c);
        chip.setThresholds(0.0, 188006.4);
        assertEquals(0x00, reg(c, 6));
        assertEquals(0xEF, reg(c, 5));
        chip.setThresholds(5.0, 100.0);
        assertEquals(0x07, reg(c, 6));
        assertEquals(4, reg(c, 5) >> 4);
        assertTrue((reg(c, 5) & 0x08) != 0);
        double[] w = chip.getThresholds();
        assertEquals(5.0, w[0], 0.5);
        assertEquals(100.0, w[1], 13.0);
        assertEquals(4 * 0x5F * 0.045, MAX44009Full.decodeThreshold(0x25, true), 1e-9);
        assertEquals(4 * 0x50 * 0.045, MAX44009Full.decodeThreshold(0x25, false), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> chip.setThresholds(10.0, 5.0));

        chip.setThresholdDelay(2.0);
        assertEquals(20, reg(c, 7));
        assertEquals(2.0, chip.getThresholdDelay(), 1e-9);
        chip.setThresholdDelay(99);
        assertEquals(255, reg(c, 7));
        chip.setThresholdDelay(-1);
        assertEquals(0, reg(c, 7));
    }

    @Test
    void interrupt() throws Exception {
        MockConnection c = mock(0, 0);
        MAX44009Full chip = new MAX44009Full(c);
        chip.enableInterrupt();
        assertEquals(0x01, reg(c, 1));
        chip.disableInterrupt();
        assertEquals(0x00, reg(c, 1));
        c.setRegister(0x00, 0x01);
        assertEquals(1, chip.pollInterrupt());
        c.setRegister(0x00, 0x00);
        assertEquals(0, chip.pollInterrupt());

        AtomicInteger calls = new AtomicInteger();
        AtomicInteger last = new AtomicInteger();
        c.setRegister(0x00, 0x01);
        chip.onInterrupt(s -> { calls.incrementAndGet(); last.set(s); });
        long end = System.currentTimeMillis() + 2000;
        while (calls.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10);
        c.setRegister(0x00, 0x00);
        chip.offInterrupt();
        assertTrue(calls.get() >= 1);
        assertEquals(1, last.get());
    }
}
