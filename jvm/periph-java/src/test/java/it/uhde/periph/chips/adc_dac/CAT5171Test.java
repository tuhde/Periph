package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CAT5171Test {

    private static byte[] last(MockConnection c) {
        List<byte[]> w = c.writes();
        return w.get(w.size() - 1);
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static MockConnection withReads(int... values) {
        MockConnection c = new MockConnection();
        for (int v : values) c.queueRead(bytes(v));
        return c;
    }

    @Test
    void constructionMakesNoBusTraffic() {
        MockConnection c = new MockConnection();
        new CAT5171Minimal(c);
        assertEquals(0, c.writes().size());
    }

    @Test
    void setWiperEncodesInstructionAndClamps() throws Exception {
        MockConnection c = new MockConnection();
        CAT5171Minimal pot = new CAT5171Minimal(c);
        pot.setWiper(100);
        assertArrayEquals(bytes(0x00, 100), last(c));
        pot.setWiper(300);
        assertArrayEquals(bytes(0x00, 255), last(c));
        pot.setWiper(-5);
        assertArrayEquals(bytes(0x00, 0), last(c));
    }

    @Test
    void getWiperIsBareRead() throws Exception {
        MockConnection c = withReads(17);
        CAT5171Minimal pot = new CAT5171Minimal(c);
        assertEquals(17, pot.getWiper());
        assertEquals(0, c.writes().size());
    }

    @Test
    void resistanceConversions() throws Exception {
        MockConnection c = new MockConnection();
        CAT5171Minimal pot = new CAT5171Minimal(c);
        pot.setResistance(50050.0);
        assertArrayEquals(bytes(0x00, 128), last(c));
        pot.setResistance(0.0);
        assertArrayEquals(bytes(0x00, 0), last(c));
        pot.setResistance(1e7);
        assertArrayEquals(bytes(0x00, 255), last(c));
        MockConnection r = withReads(128, 0);
        CAT5171Minimal pot2 = new CAT5171Minimal(r);
        assertEquals(50050.0, pot2.getResistance(), 0.01);
        assertEquals(50.0, pot2.getResistance(), 0.01);
    }

    @Test
    void fullHelpers() throws Exception {
        MockConnection c = new MockConnection();
        CAT5171Full pot = new CAT5171Full(c);
        pot.setRatio(0.25);
        assertArrayEquals(bytes(0x00, 64), last(c));
        pot.setRatio(1.0);
        assertArrayEquals(bytes(0x00, 255), last(c));
        pot.setResistanceWa(50050.0);
        assertArrayEquals(bytes(0x00, 128), last(c));

        assertEquals(0.5, new CAT5171Full(withReads(128)).getRatio(), 1e-9);
        assertEquals(75050.0, new CAT5171Full(withReads(64)).getResistanceWa(), 0.01);

        MockConnection i = withReads(250, 250, 128);
        CAT5171Full inc = new CAT5171Full(i);
        assertEquals(253, inc.increment(3));
        assertArrayEquals(bytes(0x00, 253), last(i));
        assertEquals(255, inc.increment(100));
        assertEquals(100, inc.increment(-28));
        assertArrayEquals(bytes(0x00, 100), last(i));
    }

    @Test
    void shutdownMidscaleReset() throws Exception {
        MockConnection c = new MockConnection();
        CAT5171Full pot = new CAT5171Full(c);
        assertFalse(pot.isShutdown());
        pot.shutdown();
        assertArrayEquals(bytes(0x20, 128), last(c));
        assertTrue(pot.isShutdown());
        pot.resume();
        assertArrayEquals(bytes(0x00, 128), last(c));
        assertFalse(pot.isShutdown());
        pot.setWiper(77);
        pot.shutdown();
        assertArrayEquals(bytes(0x20, 77), last(c));
        pot.setWiper(50);
        assertArrayEquals(bytes(0x20, 50), last(c));
        pot.resume();
        assertArrayEquals(bytes(0x00, 50), last(c));
        pot.midscale();
        assertArrayEquals(bytes(0x40, 0x80), last(c));
        pot.shutdown();
        pot.midscale();
        assertArrayEquals(bytes(0x60, 0x80), last(c));
        assertTrue(pot.isShutdown());
        pot.reset();
        assertArrayEquals(bytes(0x40, 0x80), last(c));
        assertFalse(pot.isShutdown());
    }

    @Test
    void setRabChangesConversionOnly() throws Exception {
        MockConnection c = withReads(128, 128);
        CAT5171Full pot = new CAT5171Full(c);
        pot.setRab(50000.0, 100.0);
        assertEquals(0, c.writes().size());
        assertEquals(25100.0, pot.getResistance(), 0.01);
        pot.setRab(50000.0);
        assertEquals(25100.0, pot.getResistance(), 0.01);
    }
}
