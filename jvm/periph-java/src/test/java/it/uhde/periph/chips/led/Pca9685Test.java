package it.uhde.periph.chips.led;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pca9685Test {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    /** Data writes only: the register-pointer writes of reads (one byte) are dropped. */
    private static List<byte[]> data(MockConnection c) {
        List<byte[]> r = new ArrayList<>();
        for (byte[] w : c.writes()) if (w.length > 1) r.add(w);
        return r;
    }

    private static byte[] last(MockConnection c) {
        List<byte[]> w = data(c);
        return w.get(w.size() - 1);
    }

    @Test
    void fullApi() throws Exception {
        MockConnection c = new MockConnection();
        Pca9685Full d = new Pca9685Full(c);
        List<byte[]> w = data(c);
        assertEquals(3, w.size());
        assertArrayEquals(b(0x00, 0x30), w.get(0));
        assertArrayEquals(b(0xFE, 5), w.get(1));
        assertArrayEquals(b(0x00, 0x20), w.get(2));

        c.writes().clear();
        d.setDuty(0, 0.5);
        assertArrayEquals(b(0x06, 0, 0, 0x00, 0x08), last(c));
        d.setDuty(15, 0.0);
        assertArrayEquals(b(0x42, 0, 0, 0, 0x10), last(c));
        d.setDuty(1, 1.0);
        assertArrayEquals(b(0x0A, 0, 0x10, 0, 0), last(c));
        assertThrows(IllegalArgumentException.class, () -> d.setDuty(16, 0.5));
        assertThrows(IllegalArgumentException.class, () -> d.setDuty(0, 1.1));
        assertThrows(IllegalArgumentException.class, () -> d.setDuty(-1, 0.5));

        d.setPwm(2, 409, 1228);
        assertArrayEquals(b(0x0E, 0x99, 0x01, 0xCC, 0x04), last(c));
        assertArrayEquals(new int[]{409, 1228}, d.getPwm(2));
        assertThrows(IllegalArgumentException.class, () -> d.setPwm(0, 4096, 1));
        assertThrows(IllegalArgumentException.class, () -> d.setPwm(0, 5, 5));

        d.setFullOn(3);
        assertArrayEquals(b(0x12, 0, 0x10, 0, 0), last(c));
        d.setFullOff(3);
        assertArrayEquals(b(0x12, 0, 0, 0, 0x10), last(c));
        d.setAllDuty(0.25);
        assertArrayEquals(b(0xFA, 0, 0, 0x00, 0x04), last(c));
        d.setAllPwm(0, 100);
        assertArrayEquals(b(0xFA, 0, 0, 100, 0), last(c));

        c.setRegister(0x00, 0x20);
        c.writes().clear();
        d.setFrequency(50);
        w = data(c);
        assertEquals(3, w.size());
        assertArrayEquals(b(0x00, 0x30), w.get(0));
        assertArrayEquals(b(0xFE, 121), w.get(1));
        assertArrayEquals(b(0x00, 0x20), w.get(2));
        assertEquals(25e6 / (4096 * 122), d.getFrequency(), 1e-6);
        d.setFrequency(1e6);
        w = data(c);
        assertArrayEquals(b(0xFE, 3), w.get(w.size() - 2));
        d.setFrequency(1);
        w = data(c);
        assertArrayEquals(b(0xFE, 255), w.get(w.size() - 2));
        c.setRegister(0x00, 0xA0);
        c.writes().clear();
        d.setFrequency(200);
        w = data(c);
        assertArrayEquals(b(0xFE, 30), w.get(1));
        assertArrayEquals(b(0x00, 0xA0), w.get(w.size() - 1));
        assertThrows(IllegalArgumentException.class, () -> d.setFrequency(0));

        c.setRegister(0xFE, 121);
        d.setPulseWidth(15, 0.0015);
        assertArrayEquals(b(0x42, 0, 0, 307 & 0xFF, 307 >> 8), last(c));

        c.setRegister(0x00, 0x20);
        d.sleep();
        assertArrayEquals(b(0x00, 0x30), last(c));
        c.setRegister(0x00, 0xB0);
        c.writes().clear();
        d.wake();
        w = data(c);
        assertArrayEquals(b(0x00, 0x20), w.get(0));
        assertArrayEquals(b(0x00, 0xA0), w.get(1));
        d.restart();
        assertArrayEquals(b(0x00, 0xA0), last(c));

        c.setRegister(0x01, 0x04);
        d.setOutputMode(true, true, Pca9685Full.OFF_HIGHZ);
        assertArrayEquals(b(0x01, 0x12), last(c));
        d.setOutputMode(false, false, Pca9685Full.OFF_LOW);
        assertArrayEquals(b(0x01, 0x04), last(c));
        assertThrows(IllegalArgumentException.class, () -> d.setOutputMode(false, false, 3));
        d.setUpdateOnAck(true);
        assertArrayEquals(b(0x01, 0x0C), last(c));
        d.setUpdateOnAck(false);
        assertArrayEquals(b(0x01, 0x04), last(c));

        c.setRegister(0x00, 0x20);
        c.writes().clear();
        d.useExternalClock();
        w = data(c);
        assertArrayEquals(b(0x00, 0x30), w.get(0));
        assertArrayEquals(b(0x00, 0x70), w.get(1));
        assertArrayEquals(b(0x00, 0x60), w.get(2));

        c.setRegister(0x00, 0x20);
        c.writes().clear();
        d.setAllcall(true);
        w = data(c);
        assertArrayEquals(b(0x05, 0xE0), w.get(0));
        assertArrayEquals(b(0x00, 0x21), w.get(1));
        d.setSubaddress(2, 0x71, true);
        w = data(c);
        assertArrayEquals(b(0x03, 0xE2), w.get(w.size() - 2));
        assertArrayEquals(b(0x00, 0x25), w.get(w.size() - 1));
        assertThrows(IllegalArgumentException.class, () -> d.setSubaddress(4, 0x71, true));
        assertThrows(IllegalArgumentException.class, () -> d.setAllcall(true, 0x80));

        c.writes().clear();
        d.reset();
        w = data(c);
        assertArrayEquals(b(0x00, 0x10), w.get(0));
        assertArrayEquals(b(0x00, 0x11), w.get(w.size() - 1));
        assertTrue(w.stream().anyMatch(x -> java.util.Arrays.equals(x, b(0xFE, 0x1E))));

        MockConnection g = new MockConnection();
        Pca9685Full.softwareReset(g);
        assertEquals(1, g.writes().size());
        assertArrayEquals(b(0x06), g.writes().get(0));

        c.setRegister(0x00, 0x11, 0x04);
        assertArrayEquals(new int[]{0x11, 0x04}, d.readMode());
        assertEquals(16, Pca9685Minimal.CHANNELS);
    }
}
