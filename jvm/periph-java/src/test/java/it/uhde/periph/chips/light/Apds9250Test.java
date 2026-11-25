package it.uhde.periph.chips.light;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Apds9250Test {

    @BeforeAll
    static void noWaits() {
        Apds9250Minimal.startWaitMs = 0;
        Apds9250Minimal.pollStepMs = 0;
    }

    private static MockConnection mock(int part) {
        MockConnection c = new MockConnection();
        c.setRegister(0x06, part);
        c.setRegister(0x07, 0x08);
        c.setRegister(0x04, 0x22);
        c.setRegister(0x05, 0x01);
        c.setRegister(0x19, 0x10);
        return c;
    }

    private static int reg(MockConnection c, int r) { return c.registers().get(r); }

    @Test
    void initSequence() throws Exception {
        MockConnection c = mock(0xB5);
        new Apds9250Full(c);
        List<int[]> seq = new ArrayList<>();
        for (byte[] w : c.writes()) if (w.length > 1) seq.add(new int[]{w[0] & 0xFF, w[1] & 0xFF});
        int[][] want = {{0x04, 0x22}, {0x05, 0x01}, {0x19, 0x10}, {0x00, 0x06}};
        assertEquals(want.length, seq.size());
        for (int i = 0; i < want.length; i++) assertArrayEquals(want[i], seq.get(i));
    }

    @Test
    void rejectsWrongPart() {
        for (int part : new int[]{0x00, 0xC5}) {
            MockConnection c = mock(part);
            assertThrows(IllegalStateException.class, () -> new Apds9250Minimal(c));
            for (byte[] w : c.writes()) assertEquals(1, w.length, "nothing may be written");
        }
    }

    @Test
    void readDecodesChannels() throws Exception {
        MockConnection c = mock(0xB5);
        Apds9250Full chip = new Apds9250Full(c);
        c.setRegister(0x0A, 0x01, 0x02, 0x03, 0x10, 0x00, 0x00, 0xFF, 0xFF, 0x0F, 0x00, 0x00, 0xF4);
        Apds9250Minimal.Counts n = chip.read();
        assertEquals(0x030201, n.ir);
        assertEquals(16, n.green);
        assertEquals(0xFFFFF, n.blue);
        assertEquals(0x40000, n.red);
        Apds9250Minimal.Counts b = chip.readChannels();
        assertEquals(0x40000, b.red);
        assertEquals(0x40000, chip.readRed());
        assertEquals(16, chip.readGreen());
        assertEquals(0xFFFFF, chip.readBlue());
        assertEquals(0x030201, chip.readIr());
    }

    @Test
    void configuration() throws Exception {
        MockConnection c = mock(0xB5);
        Apds9250Full chip = new Apds9250Full(c);
        chip.setResolution(20);
        assertEquals(0x02, reg(c, 0x04));
        assertEquals(20, chip.getResolution());
        chip.setResolution(13);
        assertEquals(0x52, reg(c, 0x04));
        assertEquals(0.003125, chip.integrationTime(), 1e-9);
        chip.setResolution(16);
        chip.setMeasurementRate(2000);
        assertEquals(0x46, reg(c, 0x04));
        assertEquals(2000, chip.getMeasurementRate());
        chip.setMeasurementRate(25);
        assertEquals(0x40, reg(c, 0x04));
        int[] gains = {1, 3, 6, 9, 18};
        for (int i = 0; i < gains.length; i++) {
            chip.setGain(gains[i]);
            assertEquals(i, reg(c, 0x05));
            assertEquals(gains[i], chip.getGain());
        }
        chip.setResolution(18);
        chip.setGain(3);
        assertEquals(10000.0, chip.normalized(3000), 1e-6);
    }

    @Test
    void invalidArgumentsWriteNothing() throws Exception {
        MockConnection c = mock(0xB5);
        Apds9250Full chip = new Apds9250Full(c);
        int before = c.writes().size();
        assertThrows(IllegalArgumentException.class, () -> chip.setGain(2));
        assertThrows(IllegalArgumentException.class, () -> chip.setResolution(12));
        assertThrows(IllegalArgumentException.class, () -> chip.setMeasurementRate(30));
        assertThrows(IllegalArgumentException.class, () -> chip.setPersistence(0));
        assertThrows(IllegalArgumentException.class, () -> chip.setPersistence(17));
        assertThrows(IllegalArgumentException.class, () -> chip.setVariance(12));
        assertThrows(IllegalArgumentException.class, () -> chip.setInterruptChannel(4));
        assertThrows(IllegalArgumentException.class, () -> chip.setThresholds(5, 4));
        assertThrows(IllegalArgumentException.class, () -> chip.setThresholds(0, 0x100000));
        assertEquals(before, c.writes().size());
    }

    @Test
    void modeEnableDisableReset() throws Exception {
        MockConnection c = mock(0xB5);
        Apds9250Full chip = new Apds9250Full(c);
        c.setRegister(0x00, 0x06);
        chip.setMode(false);
        assertEquals(0x02, reg(c, 0x00));
        chip.setMode(true);
        assertEquals(0x06, reg(c, 0x00));
        chip.disable();
        assertEquals(0x04, reg(c, 0x00));
        chip.enable();
        assertEquals(0x06, reg(c, 0x00));
        chip.softReset();
        byte[] last = c.writes().get(c.writes().size() - 1);
        assertEquals(2, last.length);
        assertEquals(0x10, last[1]);
        assertArrayEquals(new int[]{0xB, 0x5}, chip.partId());
    }

    @Test
    void thresholds() throws Exception {
        MockConnection c = mock(0xB5);
        Apds9250Full chip = new Apds9250Full(c);
        chip.setThresholds(0x12345, 0xABCDE);
        assertEquals(0x45, reg(c, 0x24));
        assertEquals(0x23, reg(c, 0x25));
        assertEquals(0x01, reg(c, 0x26));
        assertEquals(0xDE, reg(c, 0x21));
        assertEquals(0xBC, reg(c, 0x22));
        assertEquals(0x0A, reg(c, 0x23));
        assertArrayEquals(new int[]{0x12345, 0xABCDE}, chip.getThresholds());
    }

    @Test
    void interruptConfiguration() throws Exception {
        MockConnection c = mock(0xB5);
        Apds9250Full chip = new Apds9250Full(c);
        chip.setInterruptChannel(Apds9250Full.CHANNEL_RED);
        assertEquals(0x20, reg(c, 0x19));
        chip.setInterruptChannel(Apds9250Full.CHANNEL_BLUE);
        chip.enableInterrupt(Apds9250Full.SOURCE_VARIATION);
        assertEquals(0x3C, reg(c, 0x19));
        chip.enableInterrupt(Apds9250Full.SOURCE_THRESHOLD);
        assertEquals(0x34, reg(c, 0x19));
        chip.disableInterrupt();
        assertEquals(0x30, reg(c, 0x19));
        chip.setPersistence(16);
        assertEquals(0xF0, reg(c, 0x1A));
        chip.setVariance(1024);
        assertEquals(7, reg(c, 0x27));
    }

    @Test
    void statusKeepsInterruptBitAcrossDataReady() throws Exception {
        MockConnection c = mock(0xB5);
        Apds9250Full chip = new Apds9250Full(c);
        c.setRegister(0x07, 0x18);
        assertTrue(chip.dataReady());
        c.setRegister(0x07, 0x00);
        assertFalse(chip.dataReady());
        assertEquals(0x10, chip.pollInterrupt());
        assertEquals(0x00, chip.pollInterrupt());
        c.setRegister(0x07, 0x20);
        assertEquals(0x20, chip.status());
    }

    @Test
    void callbackReceivesStatus() throws Exception {
        MockConnection c = mock(0xB5);
        Apds9250Full chip = new Apds9250Full(c);
        c.setRegister(0x07, 0x00);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger last = new AtomicInteger();
        c.setRegister(0x07, 0x10);
        chip.onInterrupt(s -> { calls.incrementAndGet(); last.set(s); });
        long end = System.currentTimeMillis() + 2000;
        while (calls.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10);
        c.setRegister(0x07, 0x00);
        chip.offInterrupt();
        assertTrue(calls.get() >= 1);
        assertEquals(0x10, last.get());
    }
}
