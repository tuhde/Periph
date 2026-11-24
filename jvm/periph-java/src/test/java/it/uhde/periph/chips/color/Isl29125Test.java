package it.uhde.periph.chips.color;

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

class Isl29125Test {

    @BeforeAll
    static void noWaits() {
        Isl29125Minimal.pollStepMs = 0;
    }

    private static MockConnection mock(int id) {
        MockConnection c = new MockConnection();
        c.setRegister(0x00, id);
        c.setRegister(0x01, 0x00);
        c.setRegister(0x02, 0x00);
        c.setRegister(0x03, 0x00);
        c.setRegister(0x08, 0x02);
        return c;
    }

    private static int reg(MockConnection c, int r) { return c.registers().get(r); }

    @Test
    void initSequence() throws Exception {
        MockConnection c = mock(0x7D);
        new Isl29125Full(c);
        List<int[]> seq = new ArrayList<>();
        for (byte[] w : c.writes()) if (w.length > 1) seq.add(new int[]{w[0] & 0xFF, w[1] & 0xFF});
        int[][] want = {{0x08, 0x00}, {0x03, 0x00}, {0x02, 0xBF}, {0x01, 0x0D}};
        assertEquals(want.length, seq.size());
        for (int i = 0; i < want.length; i++) assertArrayEquals(want[i], seq.get(i));
    }

    @Test
    void rejectsWrongId() {
        MockConnection c = mock(0x00);
        assertThrows(IllegalStateException.class, () -> new Isl29125Minimal(c));
        for (byte[] w : c.writes()) assertEquals(1, w.length, "nothing may be written");
    }

    @Test
    void readDecodesChannels() throws Exception {
        MockConnection c = mock(0x7D);
        Isl29125Full chip = new Isl29125Full(c);
        c.setRegister(0x08, 0x02);
        c.setRegister(0x09, 0x34, 0x12, 0xFF, 0xFF, 0x01, 0x00);
        Isl29125Minimal.Counts n = chip.read();
        assertEquals(0x1234, n.green);
        assertEquals(0xFFFF, n.red);
        assertEquals(1, n.blue);
        Isl29125Minimal.Counts b = chip.readRgb();
        assertEquals(0xFFFF, b.red);
        assertEquals(0xFFFF, chip.readRed());
        assertEquals(0x1234, chip.readGreen());
        assertEquals(1, chip.readBlue());
        assertEquals(0x7D, chip.deviceId());
    }

    @Test
    void configuration() throws Exception {
        MockConnection c = mock(0x7D);
        Isl29125Full chip = new Isl29125Full(c);
        c.setRegister(0x01, 0x0D);
        for (int mode = 0; mode < 8; mode++) {
            chip.setMode(mode);
            assertEquals(0x08 | mode, reg(c, 0x01));
        }
        chip.setRange(375);
        assertEquals(0x07, reg(c, 0x01));
        chip.setRange(10000);
        assertEquals(0x0F, reg(c, 0x01));
        chip.setResolution(12);
        assertEquals(0x1F, reg(c, 0x01));
        chip.setResolution(16);
        assertEquals(0x0F, reg(c, 0x01));
        chip.setSync(true);
        assertEquals(0x2F, reg(c, 0x01));
        chip.setSync(false);
        assertEquals(0x0F, reg(c, 0x01));
        chip.setIrCompensation(false, 0x3F);
        assertEquals(0x3F, reg(c, 0x02));
        chip.setIrCompensation(true, 0x3F);
        assertEquals(0xBF, reg(c, 0x02));
    }

    @Test
    void invalidArgumentsWriteNothing() throws Exception {
        MockConnection c = mock(0x7D);
        Isl29125Full chip = new Isl29125Full(c);
        int before = c.writes().size();
        assertThrows(IllegalArgumentException.class, () -> chip.setMode(8));
        assertThrows(IllegalArgumentException.class, () -> chip.setRange(100));
        assertThrows(IllegalArgumentException.class, () -> chip.setResolution(8));
        assertThrows(IllegalArgumentException.class, () -> chip.setIrCompensation(false, 64));
        assertThrows(IllegalArgumentException.class, () -> chip.setPersistence(3));
        assertThrows(IllegalArgumentException.class, () -> chip.setInterruptChannel(4));
        assertThrows(IllegalArgumentException.class, () -> chip.setThresholds(5, 4));
        assertThrows(IllegalArgumentException.class, () -> chip.setThresholds(0, 0x10000));
        assertEquals(before, c.writes().size());
    }

    @Test
    void softResetWritesMagicValue() throws Exception {
        MockConnection c = mock(0x7D);
        Isl29125Full chip = new Isl29125Full(c);
        int before = c.writes().size();
        chip.softReset();
        byte[] w = c.writes().get(before);
        assertEquals(2, w.length);
        assertEquals(0x00, w[0] & 0xFF);
        assertEquals(0x46, w[1] & 0xFF);
    }

    @Test
    void thresholds() throws Exception {
        MockConnection c = mock(0x7D);
        Isl29125Full chip = new Isl29125Full(c);
        chip.setThresholds(0x1234, 0xABCD);
        assertEquals(0x34, reg(c, 0x04));
        assertEquals(0x12, reg(c, 0x05));
        assertEquals(0xCD, reg(c, 0x06));
        assertEquals(0xAB, reg(c, 0x07));
        assertArrayEquals(new int[]{0x1234, 0xABCD}, chip.getThresholds());
    }

    @Test
    void interruptConfiguration() throws Exception {
        MockConnection c = mock(0x7D);
        Isl29125Full chip = new Isl29125Full(c);
        chip.setInterruptChannel(Isl29125Full.CHANNEL_RED);
        assertEquals(0x02, reg(c, 0x03));
        chip.setInterruptChannel(Isl29125Full.CHANNEL_BLUE);
        assertEquals(0x03, reg(c, 0x03));
        chip.setInterruptChannel(Isl29125Full.CHANNEL_NONE);
        chip.setPersistence(8);
        assertEquals(0x0C, reg(c, 0x03));
        chip.setPersistence(4);
        chip.setInterruptChannel(Isl29125Full.CHANNEL_GREEN);
        assertEquals(0x09, reg(c, 0x03));
        chip.enableInterrupt(Isl29125Full.SOURCE_CONVERSION);
        assertEquals(0x19, reg(c, 0x03));
        chip.disableInterrupt(Isl29125Full.SOURCE_CONVERSION);
        assertEquals(0x09, reg(c, 0x03));
        chip.disableInterrupt(Isl29125Full.SOURCE_THRESHOLD);
        assertEquals(0x08, reg(c, 0x03));
        chip.enableInterrupt(Isl29125Full.SOURCE_THRESHOLD);
        assertEquals(0x09, reg(c, 0x03));
        chip.setInterruptChannel(Isl29125Full.CHANNEL_RED);
        chip.enableInterrupt(Isl29125Full.SOURCE_THRESHOLD);
        assertEquals(0x0A, reg(c, 0x03));
    }

    @Test
    void dataReadyKeepsThresholdFlag() throws Exception {
        MockConnection c = mock(0x7D);
        Isl29125Full chip = new Isl29125Full(c);
        c.setRegister(0x08, 0x03);
        assertTrue(chip.dataReady());
        c.setRegister(0x08, 0x00);
        assertFalse(chip.dataReady());
        assertEquals(0x03, chip.pollInterrupt());
        assertEquals(0x00, chip.pollInterrupt());
        c.setRegister(0x08, 0x04);
        assertEquals(0x04, chip.status());
    }

    @Test
    void callbackReceivesStatus() throws Exception {
        MockConnection c = mock(0x7D);
        Isl29125Full chip = new Isl29125Full(c);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger last = new AtomicInteger();
        c.setRegister(0x08, 0x01);
        chip.onInterrupt(s -> { calls.incrementAndGet(); last.set(s); });
        long end = System.currentTimeMillis() + 2000;
        while (calls.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10);
        c.setRegister(0x08, 0x00);
        chip.offInterrupt();
        assertTrue(calls.get() >= 1);
        assertEquals(0x01, last.get());
    }
}
