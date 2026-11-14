package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AD5252Test {

    private static byte[] last(MockConnection c) {
        List<byte[]> w = c.writes();
        return w.get(w.size() - 1);
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static void assertWrites(MockConnection c, int... singles) {
        List<byte[]> expected = new ArrayList<>();
        for (int s : singles) expected.add(bytes(s));
        assertEquals(expected.size(), c.writes().size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), c.writes().get(i));
        }
    }

    @Test
    void constructionMakesNoBusTraffic() {
        MockConnection c = new MockConnection();
        new AD5252Minimal(c);
        assertEquals(0, c.writes().size());
    }

    @Test
    void setWiperEncodesInstructionAndClamps() throws Exception {
        MockConnection c = new MockConnection();
        AD5252Minimal pot = new AD5252Minimal(c);
        pot.setWiper(0, 100);
        assertArrayEquals(bytes(0x01, 100), last(c));
        pot.setWiper(1, 200);
        assertArrayEquals(bytes(0x03, 200), last(c));
        pot.setWiper(0, 300);
        assertArrayEquals(bytes(0x01, 255), last(c));
        pot.setWiper(0, -5);
        assertArrayEquals(bytes(0x01, 0), last(c));
        assertThrows(IllegalArgumentException.class, () -> pot.setWiper(2, 1));
    }

    @Test
    void getWiperUsesInstructionAsPointer() throws Exception {
        MockConnection c = new MockConnection();
        c.setRegister(0x01, 17);
        c.setRegister(0x03, 99);
        AD5252Minimal pot = new AD5252Minimal(c);
        assertEquals(17, pot.getWiper(0));
        assertEquals(99, pot.getWiper(1));
        assertWrites(c, 0x01, 0x03);
    }

    @Test
    void resistanceConversions() throws Exception {
        MockConnection c = new MockConnection();
        AD5252Minimal pot = new AD5252Minimal(c);
        pot.setResistance(0, 5075.0);
        assertArrayEquals(bytes(0x01, 128), last(c));
        pot.setResistance(0, 0.0);
        assertArrayEquals(bytes(0x01, 0), last(c));
        pot.setResistance(1, 1e6);
        assertArrayEquals(bytes(0x03, 255), last(c));
        c.setRegister(0x01, 128);
        c.setRegister(0x03, 0);
        assertEquals(5075.0, pot.getResistance(0), 0.01);
        assertEquals(75.0, pot.getResistance(1), 0.01);
    }

    @Test
    void setBothIsOneTransactionAndRatioHelpers() throws Exception {
        MockConnection c = new MockConnection();
        AD5252Full pot = new AD5252Full(c);
        pot.setBoth(1, 2);
        assertEquals(1, c.writes().size());
        assertArrayEquals(bytes(0x01, 1, 2), last(c));
        pot.setRatio(0, 0.25);
        assertArrayEquals(bytes(0x01, 64), last(c));
        pot.setRatio(0, 1.0);
        assertArrayEquals(bytes(0x01, 255), last(c));
        pot.setResistanceWa(1, 5075.0);
        assertArrayEquals(bytes(0x03, 128), last(c));
        c.setRegister(0x03, 128);
        assertEquals(0.5, pot.getRatio(1), 0.0);
        assertEquals(5075.0, pot.getResistanceWa(1), 0.01);
    }

    @Test
    void quickCommands() throws Exception {
        MockConnection c = new MockConnection();
        AD5252Full pot = new AD5252Full(c);
        pot.increment(0); pot.increment(1); pot.increment();
        pot.decrement(0); pot.decrement(1); pot.decrement();
        assertWrites(c, 0xD1, 0xD3, 0xD8, 0xA9, 0xAB, 0xB0);
        c.writes().clear();
        pot.doubleCode(0); pot.doubleCode(1); pot.doubleCode();
        pot.halve(0); pot.halve(1); pot.halve();
        assertWrites(c, 0xC1, 0xC3, 0xC8, 0x99, 0x9B, 0xA0);
        c.writes().clear();
        pot.nop();
        assertWrites(c, 0x80);
        assertThrows(IllegalArgumentException.class, () -> pot.increment(2));
    }

    @Test
    void storeRestoreResetWithAckPolling() throws Exception {
        MockConnection c = new MockConnection();
        AD5252Full pot = new AD5252Full(c);
        pot.store(1);
        assertWrites(c, 0x93, 0x80);
        c.writes().clear();
        c.failNextWrites(2, 1);
        pot.store(0);
        assertWrites(c, 0x91, 0x80, 0x80, 0x80);
        c.writes().clear();
        pot.store();
        assertWrites(c, 0x91, 0x80, 0x93, 0x80);
        c.writes().clear();
        pot.restore(0);
        assertWrites(c, 0x89, 0x80);
        c.writes().clear();
        pot.restore();
        assertWrites(c, 0x89, 0x8B, 0x80);
        c.writes().clear();
        pot.reset();
        assertWrites(c, 0xB8, 0x80);
    }

    @Test
    void storeTimesOut() {
        MockConnection c = new MockConnection();
        AD5252Full pot = new AD5252Full(c);
        c.failNextWrites(1000, 1);
        assertThrows(IOException.class, () -> pot.store(0));
    }

    @Test
    void userEememAndTolerance() throws Exception {
        MockConnection c = new MockConnection();
        c.setRegister(0x24, 0x5A);
        c.setRegister(0x2F, 0xA5);
        c.setRegister(0x3A, 0x1C, 0x0F);
        c.setRegister(0x3E, 0x82, 0x80);
        AD5252Full pot = new AD5252Full(c);
        assertEquals(0x5A, pot.readEemem(4));
        assertEquals(0xA5, pot.readEemem(15));
        c.writes().clear();
        pot.writeEemem(9, 0x42);
        assertEquals(2, c.writes().size());
        assertArrayEquals(bytes(0x29, 0x42), c.writes().get(0));
        assertArrayEquals(bytes(0x80), c.writes().get(1));
        assertThrows(IllegalArgumentException.class, () -> pot.readEemem(3));
        assertThrows(IllegalArgumentException.class, () -> pot.writeEemem(16, 0));
        assertEquals(28.05859375, pot.readTolerance(0), 1e-6);
        assertEquals(-2.5, pot.readTolerance(1), 1e-9);
        assertEquals(12805.859375, pot.rabActual(0), 0.01);
    }

    @Test
    void setRabChangesConversionsOnly() throws Exception {
        MockConnection c = new MockConnection();
        c.setRegister(0x01, 128);
        AD5252Full pot = new AD5252Full(c);
        pot.setRab(50000.0, 100.0);
        assertEquals(0, c.writes().size());
        assertEquals(25100.0, pot.getResistance(0), 0.01);
        pot.setRab(10000.0);
        assertEquals(5100.0, pot.getResistance(0), 0.01);
    }
}
