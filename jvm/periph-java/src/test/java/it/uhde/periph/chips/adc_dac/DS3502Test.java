package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DS3502Test {

    private static MockConnection mock(int wr) {
        MockConnection c = new MockConnection();
        c.setRegister(0x00, wr);
        c.setRegister(0x02, 0x00);
        return c;
    }

    private static int wr(MockConnection c) { return c.registers().get(0x00); }
    private static int cr(MockConnection c) { return c.registers().get(0x02); }

    private static List<byte[]> dataWrites(MockConnection c) {
        List<byte[]> out = new ArrayList<>();
        for (byte[] w : c.writes()) if (w.length > 1) out.add(w);
        return out;
    }

    @Test
    void initSetsModeAndKeepsWiper() throws Exception {
        MockConnection c = mock(0x23);
        DS3502Full pot = new DS3502Full(c);
        assertEquals(0x80, cr(c));
        assertEquals(1, dataWrites(c).size());
        assertEquals(0x02, dataWrites(c).get(0)[0]);
        assertEquals(0x23, wr(c));
        assertEquals(0x23, pot.getWiper());
    }

    @Test
    void initRetriesOnceAfterNack() throws Exception {
        MockConnection c = mock(0x40);
        c.failNextWrites(1, 0);
        new DS3502Minimal(c);
        assertEquals(0x80, cr(c));
        assertEquals(2, c.writes().stream().filter(w -> w.length > 1).count());

        MockConnection d = mock(0x40);
        d.failNextWrites(2, 0);
        assertThrows(IOException.class, () -> new DS3502Minimal(d));
    }

    @Test
    void wiperAndVoltage() throws Exception {
        MockConnection c = mock(0x40);
        DS3502Minimal pot = new DS3502Minimal(c, 15.0, 0.0);
        pot.setWiper(100);
        assertEquals(100, wr(c));
        pot.setWiper(300);
        assertEquals(127, wr(c));
        pot.setWiper(-5);
        assertEquals(0, wr(c));
        c.setRegister(0x00, 0xFF);
        assertEquals(127, pot.getWiper());
        pot.setVoltage(6.0);
        assertEquals(51, wr(c));
        assertEquals(15.0 * 51 / 127, pot.getVoltage(), 1e-9);
        pot.setVoltage(99);
        assertEquals(127, wr(c));
        pot.setVoltage(-3);
        assertEquals(0, wr(c));

        MockConnection c2 = mock(0x40);
        new DS3502Minimal(c2, 12.0, 2.0).setVoltage(7.0);
        assertEquals(64, wr(c2));
        MockConnection c3 = mock(0x40);
        new DS3502Minimal(c3, 5.0, 5.0).setVoltage(5.0);
        assertEquals(0, wr(c3));
    }

    @Test
    void ratioResistanceIncrement() throws Exception {
        MockConnection c = mock(0x40);
        DS3502Full pot = new DS3502Full(c);
        pot.setRatio(0.5);
        assertEquals(64, wr(c));
        pot.setRatio(2.0);
        assertEquals(127, wr(c));
        assertEquals(1.0, pot.getRatio(), 0.0);
        c.setRegister(0x00, 0);
        assertEquals(0.0, pot.getResistance('L'), 0.0);
        assertEquals(10000.0, pot.getResistance('H'), 0.0);
        c.setRegister(0x00, 127);
        assertEquals(10000.0, pot.getResistance(), 0.0);
        assertEquals(0.0, pot.getResistance('H'), 0.0);
        assertThrows(IllegalArgumentException.class, () -> pot.getResistance('X'));
        c.setRegister(0x00, 0x40);
        assertEquals(69, pot.increment(5));
        assertEquals(69, wr(c));
        assertEquals(59, pot.increment(-10));
        c.setRegister(0x00, 125);
        assertEquals(127, pot.increment(10));
        c.setRegister(0x00, 3);
        assertEquals(0, pot.increment(-10));
    }

    @Test
    void modeAndStoreWiper() throws Exception {
        MockConnection c = mock(0x40);
        DS3502Full pot = new DS3502Full(c);
        assertFalse(pot.getMode());
        pot.setMode(true);
        assertEquals(0x00, cr(c));
        assertTrue(pot.getMode());
        pot.setMode(false);
        assertEquals(0x80, cr(c));

        int n = dataWrites(c).size();
        pot.storeWiper(77);
        List<byte[]> seq = dataWrites(c).subList(n, dataWrites(c).size());
        assertEquals(3, seq.size());
        assertEquals(0x02, seq.get(0)[0]);
        assertEquals(0x00, seq.get(0)[1]);
        assertEquals(0x00, seq.get(1)[0]);
        assertEquals(77, seq.get(1)[1]);
        assertEquals(0x02, seq.get(2)[0]);
        assertEquals((byte) 0x80, seq.get(2)[1]);
        assertEquals(0x80, cr(c));
        assertEquals(77, pot.getWiper());
        c.setRegister(0x00, 33);
        pot.storeWiper();
        assertEquals(33, wr(c));
    }

    @Test
    void storeWiperRestoresModeOnTimeout() throws Exception {
        MockConnection c = mock(0x40);
        DS3502Full pot = new DS3502Full(c);
        c.failNextWrites(31, 2); // CR <- 0 and WR <- code succeed; all 31 polls NACK
        assertThrows(IOException.class, () -> pot.storeWiper(10));
        assertEquals(0x80, cr(c));
    }

    @Test
    void waitReadyAndEndpoints() throws Exception {
        MockConnection c = mock(0x40);
        DS3502Full pot = new DS3502Full(c);
        c.failNextWrites(3, 0);
        pot.waitReady();
        c.failNextWrites(1000, 0);
        assertThrows(IOException.class, () -> pot.waitReady(5));
        c.failNextWrites(0, 0);
        int n = c.writes().size();
        pot.setEndpoints(10.0, 0.0);
        assertEquals(n, c.writes().size());
        c.setRegister(0x00, 127);
        assertEquals(10.0, pot.getVoltage(), 1e-9);
    }
}
