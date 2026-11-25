package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AD5243Test {

    private static byte[] last(MockConnection c) {
        List<byte[]> w = c.writes();
        return w.get(w.size() - 1);
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    @Test
    void constructionMakesNoBusTraffic() {
        MockConnection c = new MockConnection();
        new AD5243Minimal(c);
        assertEquals(0, c.writes().size());
    }

    @Test
    void setWiperEncodesInstructionAndClamps() throws Exception {
        MockConnection c = new MockConnection();
        AD5243Minimal pot = new AD5243Minimal(c);
        pot.setWiper(0, 100);
        assertArrayEquals(bytes(0x00, 100), last(c));
        pot.setWiper(1, 200);
        assertArrayEquals(bytes(0x80, 200), last(c));
        pot.setWiper(0, 300);
        assertArrayEquals(bytes(0x00, 255), last(c));
        pot.setWiper(0, -5);
        assertArrayEquals(bytes(0x00, 0), last(c));
        assertThrows(IllegalArgumentException.class, () -> pot.setWiper(2, 1));
    }

    @Test
    void getWiperSelectsChannelByInstruction() throws Exception {
        MockConnection c = new MockConnection();
        c.setRegister(0x00, 17);
        c.setRegister(0x80, 99);
        AD5243Minimal pot = new AD5243Minimal(c);
        assertEquals(17, pot.getWiper(0));
        assertEquals(99, pot.getWiper(1));
    }

    @Test
    void resistanceConversions() throws Exception {
        MockConnection c = new MockConnection();
        AD5243Minimal pot = new AD5243Minimal(c);
        pot.setResistance(0, 5320.0);
        assertArrayEquals(bytes(0x00, 128), last(c));
        pot.setResistance(0, 0.0);
        assertArrayEquals(bytes(0x00, 0), last(c));
        pot.setResistance(1, 1e6);
        assertArrayEquals(bytes(0x80, 255), last(c));
        c.setRegister(0x00, 128);
        c.setRegister(0x80, 0);
        assertEquals(5320.0, pot.getResistance(0), 0.01);
        assertEquals(320.0, pot.getResistance(1), 0.01);
    }

    @Test
    void fullHelpers() throws Exception {
        MockConnection c = new MockConnection();
        AD5243Full pot = new AD5243Full(c);
        pot.setBoth(1, 2);
        assertArrayEquals(bytes(0x00, 1), c.writes().get(0));
        assertArrayEquals(bytes(0x80, 2), c.writes().get(1));
        pot.setRatio(0, 0.25);
        assertArrayEquals(bytes(0x00, 64), last(c));
        pot.setRatio(0, 1.0);
        assertArrayEquals(bytes(0x00, 255), last(c));
        pot.setResistanceWa(1, 5320.0);
        assertArrayEquals(bytes(0x80, 128), last(c));

        c.setRegister(0x00, 250);
        c.setRegister(0x80, 128);
        assertEquals(0.5, pot.getRatio(1), 1e-9);
        assertEquals(5320.0, pot.getResistanceWa(1), 0.01);
        assertEquals(253, pot.increment(0, 3));
        assertArrayEquals(bytes(0x00, 253), last(c));
        assertEquals(255, pot.increment(0, 100));
        assertEquals(100, pot.increment(1, -28));
    }

    @Test
    void shutdownResumeReset() throws Exception {
        MockConnection c = new MockConnection();
        AD5243Full pot = new AD5243Full(c);
        pot.setWiper(1, 77);
        assertFalse(pot.isShutdown(1));
        pot.shutdown(1);
        assertArrayEquals(bytes(0xC0, 77), last(c));
        assertTrue(pot.isShutdown(1));
        assertFalse(pot.isShutdown(0));
        pot.setWiper(1, 50);
        assertArrayEquals(bytes(0xC0, 50), last(c));
        pot.resume(1);
        assertArrayEquals(bytes(0x80, 50), last(c));
        assertFalse(pot.isShutdown(1));
        pot.shutdown(0);
        assertArrayEquals(bytes(0x40, 128), last(c));
        pot.reset();
        List<byte[]> w = c.writes();
        assertArrayEquals(bytes(0x00, 128), w.get(w.size() - 2));
        assertArrayEquals(bytes(0x80, 128), w.get(w.size() - 1));
        assertFalse(pot.isShutdown(0));
    }

    @Test
    void setRabChangesConversionOnly() throws Exception {
        MockConnection c = new MockConnection();
        c.setRegister(0x00, 128);
        AD5243Full pot = new AD5243Full(c);
        pot.setRab(50000.0, 100.0);
        assertEquals(0, c.writes().size());
        assertEquals(25200.0, pot.getResistance(0), 0.01);
        pot.setRab(10000.0);
        assertEquals(5200.0, pot.getResistance(0), 0.01);
    }
}
