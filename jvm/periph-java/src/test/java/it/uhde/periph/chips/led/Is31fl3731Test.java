package it.uhde.periph.chips.led;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Is31fl3731Test {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    /** reg followed by n copies of v. */
    private static byte[] rep(int reg, int n, int v) {
        byte[] r = new byte[n + 1];
        Arrays.fill(r, (byte) v);
        r[0] = (byte) reg;
        return r;
    }

    private static byte[] last(MockConnection c) {
        List<byte[]> w = c.writes();
        return w.get(w.size() - 1);
    }

    @Test
    void fullApi() throws Exception {
        MockConnection c = new MockConnection();
        Is31fl3731Full d = new Is31fl3731Full(c);
        List<byte[]> w = c.writes();

        assertEquals(10, w.size());
        assertArrayEquals(b(0xFD, 0x0B), w.get(0));
        assertArrayEquals(b(0x0A, 0x00), w.get(1));
        assertArrayEquals(b(0x00, 0x00, 0x00), w.get(2));
        assertArrayEquals(b(0x05, 0x00), w.get(3));
        assertArrayEquals(b(0xFD, 0x00), w.get(4));
        assertArrayEquals(rep(0x00, 18, 0xFF), w.get(5));
        assertArrayEquals(rep(0x12, 18, 0), w.get(6));
        assertArrayEquals(rep(0x24, 144, 0), w.get(7));
        assertArrayEquals(b(0xFD, 0x0B), w.get(8));
        assertArrayEquals(b(0x0A, 0x01), w.get(9));

        w.clear();
        d.setPixel(2, 5, 200);
        assertEquals(2, w.size());
        assertArrayEquals(b(0xFD, 0x00), w.get(0));
        assertArrayEquals(b(0x24 + 37, 200), w.get(1));
        w.clear();
        d.setPixel(8, 15, 1);
        assertEquals(1, w.size());
        assertArrayEquals(b(0x24 + 143, 1), w.get(0));
        assertEquals(200, d.getPixel(2, 5));
        assertEquals(0, d.getPixel(0, 0));
        assertThrows(IllegalArgumentException.class, () -> d.setPixel(9, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> d.setPixel(0, 16, 0));
        assertThrows(IllegalArgumentException.class, () -> d.setPixel(0, 0, 256));

        d.fill(0x40);
        assertArrayEquals(rep(0x24, 144, 0x40), last(c));
        d.clear();
        assertArrayEquals(rep(0x24, 144, 0), last(c));
        assertEquals(0, d.getPixel(2, 5));

        byte[] ramp = new byte[144];
        for (int i = 0; i < 144; i++) ramp[i] = (byte) i;
        d.setPixels(ramp);
        byte[] expRamp = new byte[145];
        expRamp[0] = 0x24;
        System.arraycopy(ramp, 0, expRamp, 1, 144);
        assertArrayEquals(expRamp, last(c));
        assertEquals(17, d.getPixel(1, 1));
        assertThrows(IllegalArgumentException.class, () -> d.setPixels(new byte[143]));

        d.setLedEnabled(1, 9, false);
        assertArrayEquals(b(0x03, 0xFD), last(c));
        d.setLedEnabled(1, 9, true);
        assertArrayEquals(b(0x03, 0xFF), last(c));
        d.setBlink(1, 9, true);
        assertArrayEquals(b(0x15, 0x02), last(c));
        d.setBlinkAll(true);
        assertArrayEquals(rep(0x12, 18, 0xFF), last(c));
        d.setBlinkAll(false);
        assertArrayEquals(rep(0x12, 18, 0), last(c));

        w.clear();
        d.selectFrame(3);
        assertEquals(1, w.size());
        assertArrayEquals(b(0xFD, 0x03), w.get(0));
        d.setPixel(0, 0, 9);
        assertArrayEquals(b(0x24, 9), last(c));
        d.copyFrame(3, 5);
        assertArrayEquals(b(0xFD, 0x05), w.get(w.size() - 2));
        byte[] copied = rep(0x24, 144, 0);
        copied[1] = 9;
        assertArrayEquals(copied, last(c));
        assertThrows(IllegalArgumentException.class, () -> d.selectFrame(8));
        assertThrows(IllegalArgumentException.class, () -> d.copyFrame(0, 8));
        w.clear();
        d.clearAllFrames();
        assertEquals(8, w.stream().filter(x -> x.length == 145).count());
        d.selectFrame(0);

        w.clear();
        d.showFrame(4);
        assertEquals(2, w.size());
        assertArrayEquals(b(0xFD, 0x0B), w.get(0));
        assertArrayEquals(b(0x00, 0x00, 0x04), w.get(1));
        d.setPictureMode();
        assertArrayEquals(b(0x00, 0x00), last(c));

        d.startAutoPlay(3, 2, 121, 1);
        assertArrayEquals(b(0x02, 0x23, 11), w.get(w.size() - 2));
        assertArrayEquals(b(0x00, 0x09), last(c));
        d.startAutoPlay(0, 0, 704, 0);
        assertArrayEquals(b(0x02, 0x00, 0x00), w.get(w.size() - 2));
        assertThrows(IllegalArgumentException.class, () -> d.startAutoPlay(0, 0, 2000, 0));
        assertThrows(IllegalArgumentException.class, () -> d.startAutoPlay(0, 8, 121, 0));
        c.setRegister(0x07, 0x15);
        d.stopAutoPlay();
        assertArrayEquals(b(0x00, 0x00, 0x05), last(c));

        d.setAudioMode(true);
        assertArrayEquals(b(0x00, 0x10), last(c));
        d.setAudioMode(false);
        assertArrayEquals(b(0x00, 0x00), last(c));
        d.setAudioSync(true);
        assertArrayEquals(b(0x06, 0x01), last(c));
        d.setAudioGain(9, true, true);
        assertArrayEquals(b(0x0B, 0x1B), last(c));
        assertThrows(IllegalArgumentException.class, () -> d.setAudioGain(4, false, false));
        assertThrows(IllegalArgumentException.class, () -> d.setAudioGain(24, false, false));
        d.setAudioRate(100);
        assertArrayEquals(b(0x0C, 100), last(c));

        d.enableBlink(true, 3);
        assertArrayEquals(b(0x05, 0x0B), last(c));
        d.setIntensityControl(true);
        assertArrayEquals(b(0x05, 0x2B), last(c));
        d.enableBlink(false, 1);
        assertArrayEquals(b(0x05, 0x21), last(c));
        assertThrows(IllegalArgumentException.class, () -> d.enableBlink(true, 8));
        d.setBreath(true, 2, 5, 3);
        assertArrayEquals(b(0x08, 0x52, 0x13), last(c));
        d.setBreath(false, 0, 0, 0);
        assertArrayEquals(b(0x08, 0x00, 0x00), last(c));

        d.shutdown(true);
        assertArrayEquals(b(0x0A, 0x00), last(c));
        d.shutdown(false);
        assertArrayEquals(b(0x0A, 0x01), last(c));

        c.setRegister(0x07, 0x13);
        assertEquals(0x13, d.frameState());
        assertEquals(3, d.currentFrame());
        assertTrue(d.movieFinished());
        c.setRegister(0x07, 0x02);
        assertFalse(d.movieFinished());

        d.selectFrame(2);
        w.clear();
        c.setRegister(0x03, 0x2A);
        assertEquals(0x2A, d.readFunctionRegister(0x03));
        assertArrayEquals(b(0xFD, 0x02), last(c));
        w.clear();
        d.writeFunctionRegister(0x03, 5);
        assertEquals(3, w.size());
        assertArrayEquals(b(0xFD, 0x0B), w.get(0));
        assertArrayEquals(b(0x03, 5), w.get(1));
        assertArrayEquals(b(0xFD, 0x02), w.get(2));
        assertThrows(IllegalArgumentException.class, () -> d.readFunctionRegister(0x0D));

        c.setRegister(0x07, 0x14);
        assertEquals(0x14, d.pollInterrupt());
        assertArrayEquals(b(0xFD, 0x02), last(c));

        assertEquals(32, Is31fl3731Full.GAMMA_32.length);
        assertEquals(64, Is31fl3731Full.GAMMA_64.length);
        assertEquals(255, Is31fl3731Full.GAMMA_64[63]);

        Is31fl3731Minimal m = new Is31fl3731Minimal(new MockConnection());
        m.setPixel(0, 0, 1);
        assertEquals(9, Is31fl3731Minimal.ROWS);
    }
}
