package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Shtc3Test {

    private static byte[] word(int code) {
        byte[] f = new byte[3];
        f[0] = (byte) (code >> 8); f[1] = (byte) code;
        f[2] = (byte) Shtc3Minimal.crc8(f, 0, 2);
        return f;
    }

    private static byte[] trh(int t, int rh) {
        byte[] a = word(t), b = word(rh), out = new byte[6];
        System.arraycopy(a, 0, out, 0, 3);
        System.arraycopy(b, 0, out, 3, 3);
        return out;
    }

    private static List<Integer> cmds(MockConnection c) {
        List<Integer> out = new ArrayList<>();
        for (byte[] w : c.writes()) out.add(((w[0] & 0xFF) << 8) | (w[1] & 0xFF));
        return out;
    }

    private MockConnection mock;
    private Shtc3Full s;

    private void make() {
        mock = new MockConnection();
        s = new Shtc3Full(mock);
    }

    @Test
    void crcReference() {
        assertEquals(0x92, Shtc3Minimal.crc8(new byte[]{(byte) 0xBE, (byte) 0xEF}, 0, 2));
        assertEquals(0xAC, Shtc3Minimal.crc8(new byte[]{0x00}, 0, 1));
    }

    @Test
    void constructorDoesNoBusAccessAndInitSequence() throws IOException {
        make();
        assertTrue(mock.writes().isEmpty());
        mock.queueRead(word(0x0807));
        s.init();
        assertEquals(List.of(0x3517, 0x805D, 0xEFC8, 0xB098), cmds(mock));
        assertNotNull(new Shtc3Minimal(new MockConnection()));
    }

    @Test
    void initRejectsWrongIdAndBadCrc() {
        make();
        mock.queueRead(word(0x0000));
        assertThrows(IOException.class, () -> s.init());
        make();
        byte[] bad = word(0x0807);
        bad[2] ^= 0x55;
        mock.queueRead(bad);
        assertThrows(IOException.class, () -> s.init());
    }

    @Test
    void readConvertsAndSendsCommands() throws IOException {
        make();
        mock.queueRead(trh(0x6666, 0x6000));
        double[] m = s.read();
        assertEquals(List.of(0x7866, 0xB098), cmds(mock));
        assertEquals(-45.0 + 175.0 * 0x6666 / 65536.0, m[0], 1e-6);
        assertEquals(37.5, m[1], 1e-6);
    }

    @Test
    void secondReadWakesFirst() throws IOException {
        make();
        mock.queueRead(trh(0x6666, 0x6000));
        mock.queueRead(trh(0x6666, 0x6000));
        s.read();
        s.read();
        assertEquals(List.of(0x7866, 0xB098, 0x3517, 0x7866, 0xB098), cmds(mock));
    }

    @Test
    void fullScaleAndClamp() throws IOException {
        make();
        mock.queueRead(trh(0xFFFF, 0xFFFF));
        double[] m = s.read();
        assertEquals(130.0, m[0], 0.01);
        assertTrue(m[1] <= 100.0 && m[1] > 99.9);
        mock.queueRead(trh(0, 0));
        m = s.read();
        assertEquals(-45.0, m[0], 1e-6);
        assertEquals(0.0, m[1], 1e-6);
    }

    @Test
    void crcMismatchRetriedOnceThenRejected() {
        make();
        byte[] bad = trh(0x6666, 0x6000);
        bad[2] ^= (byte) 0xFF;
        mock.queueRead(bad);
        mock.queueRead(bad);
        assertThrows(IOException.class, () -> s.read());
        assertEquals(List.of(0x7866, 0x7866, 0xB098), cmds(mock));
    }

    @Test
    void crcGlitchRecovered() throws IOException {
        make();
        byte[] bad = trh(0x6666, 0x6000);
        bad[5] ^= (byte) 0xFF;
        mock.queueRead(bad);
        mock.queueRead(trh(0x6666, 0x6000));
        assertEquals(37.5, s.read()[1], 1e-6);
        assertEquals(List.of(0x7866, 0x7866, 0xB098), cmds(mock));
    }

    @Test
    void singleValuesAndRaw() throws IOException {
        make();
        mock.queueRead(trh(0x4000, 0x4000));
        mock.queueRead(trh(0x4000, 0x4000));
        mock.queueRead(trh(0x1234, 0x5678));
        assertEquals(25.0, s.readHumidity(), 1e-6);
        assertEquals(-45.0 + 175.0 * 0.25, s.readTemperature(), 1e-6);
        assertArrayEquals(new int[]{0x1234, 0x5678}, s.readRaw());
    }

    @Test
    void modeCommands() throws IOException {
        make();
        for (int i = 0; i < 3; i++) mock.queueRead(trh(0x6666, 0x6000));
        s.setLowPower(true);
        s.read();
        s.setClockStretching(true);
        s.read();
        s.setLowPower(false);
        s.read();
        assertEquals(List.of(0x609C, 0xB098, 0x3517, 0x6458, 0xB098, 0x3517, 0x7CA2, 0xB098), cmds(mock));
    }

    @Test
    void sleepControl() throws IOException {
        make();
        mock.queueRead(trh(0x6666, 0x6000));
        mock.queueRead(trh(0x6666, 0x6000));
        s.setSleepBetween(false);
        s.read();
        s.read();
        s.sleep();
        s.wake();
        assertEquals(List.of(0x7866, 0x7866, 0xB098, 0x3517), cmds(mock));
    }

    @Test
    void softResetAndReadId() throws IOException {
        make();
        s.softReset();
        assertEquals(List.of(0x805D), cmds(mock));
        make();
        mock.queueRead(word(0xAB07));
        assertEquals(0xAB07, s.readId());
        assertEquals(List.of(0xEFC8), cmds(mock));
        s.sleep();
        mock.queueRead(word(0x0807));
        assertEquals(0x0807, s.readId() & 0x0FFF);
        assertEquals(List.of(0xEFC8, 0xB098, 0x3517, 0xEFC8, 0xB098), cmds(mock));
        byte[] bad = word(0x0807);
        bad[2] ^= 0x55;
        mock.queueRead(bad);
        assertThrows(IOException.class, () -> s.readId());
    }

    @Test
    void dewPoint() throws IOException {
        make();
        int tRaw = (int) Math.round((25.0 + 45.0) * 65536.0 / 175.0);
        mock.queueRead(trh(tRaw, 0x8000));
        assertEquals(13.86, s.dewPoint(), 0.15);
        mock.queueRead(trh(tRaw, 0));
        assertTrue(s.dewPoint() < -20.0);
    }
}
