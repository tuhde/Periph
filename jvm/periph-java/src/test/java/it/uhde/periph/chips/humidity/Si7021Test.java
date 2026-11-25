package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Si7021Test {

    private static byte[] frame(int code) {
        byte[] f = new byte[3];
        f[0] = (byte) (code >> 8); f[1] = (byte) code;
        f[2] = (byte) Si7021Minimal.crc8(f, 0, 2);
        return f;
    }

    private static int rhCode(double rh) { return (int) Math.round((rh + 6.0) * 65536.0 / 125.0); }
    private static int tCode(double t) { return (int) Math.round((t + 46.85) * 65536.0 / 175.72); }
    private static byte[] tBytes(double t) { return new byte[]{(byte) (tCode(t) >> 8), (byte) tCode(t)}; }

    private static List<Integer> cmds(MockConnection c) {
        List<Integer> out = new ArrayList<>();
        for (byte[] w : c.writes()) out.add(w[0] & 0xFF);
        return out;
    }

    private static boolean lastWrite(MockConnection c, int a, int b) {
        byte[] w = c.writes().get(c.writes().size() - 1);
        return w.length == 2 && (w[0] & 0xFF) == a && (w[1] & 0xFF) == b;
    }

    private MockConnection mock;
    private Si7021Full s;

    private void make() {
        mock = new MockConnection();
        s = new Si7021Full(mock);
    }

    @Test
    void crcReference() {
        assertEquals(0x00, Si7021Minimal.crc8(new byte[]{0, 0}, 0, 2));
        assertEquals(0x31, Si7021Minimal.crc8(new byte[]{1}, 0, 1));
    }

    @Test
    void initDoesNoBusAccessAndReadUsesF5ThenE0() throws IOException {
        make();
        assertTrue(mock.writes().isEmpty());
        mock.queueRead(frame(rhCode(50.0)));
        mock.queueRead(tBytes(25.0));
        double[] v = s.read();
        assertEquals(List.of(0xF5, 0xE0), cmds(mock));
        assertEquals(25.0, v[0], 0.02);
        assertEquals(50.0, v[1], 0.02);
        assertNotNull(new Si7021Minimal(new MockConnection()));
    }

    @Test
    void readPollsWhileNacked() throws IOException {
        make();
        mock.failNextReads(3);
        mock.queueRead(frame(rhCode(40.0)));
        mock.queueRead(tBytes(20.0));
        assertEquals(40.0, s.read()[1], 0.02);
    }

    @Test
    void timeoutWhenNeverReady() {
        make();
        mock.failNextReads(1000);
        assertThrows(IOException.class, () -> s.read());
    }

    @Test
    void crcMismatchThrows() {
        make();
        byte[] bad = frame(0x6000);
        bad[2] ^= (byte) 0xFF;
        mock.queueRead(bad);
        assertThrows(IOException.class, () -> s.read());
    }

    @Test
    void humidityIsClamped() throws IOException {
        make();
        mock.queueRead(frame(0x0000)); mock.queueRead(new byte[]{0, 0});
        assertEquals(0.0, s.read()[1], 1e-9);
        mock.queueRead(frame(0xFFFC)); mock.queueRead(new byte[]{0, 0});
        assertEquals(100.0, s.read()[1], 1e-9);
    }

    @Test
    void singleValuesAndRaw() throws IOException {
        make();
        mock.queueRead(frame(rhCode(30.0)));
        assertEquals(30.0, s.readHumidity(), 0.02);
        mock.queueRead(frame(tCode(-10.0)));
        assertEquals(-10.0, s.readTemperature(), 0.02);
        mock.queueRead(tBytes(20.0));
        assertEquals(20.0, s.readTemperaturePrevious(), 0.02);
        assertEquals(List.of(0xF5, 0xF3, 0xE0), cmds(mock));
        mock.queueRead(frame(0x1234));
        assertEquals(0x1234, s.readRawHumidity());
        mock.queueRead(frame(0x4321));
        assertEquals(0x4321, s.readRawTemperature());
    }

    @Test
    void holdMasterCommands() throws IOException {
        make();
        s.setClockStretching(true);
        mock.queueRead(frame(rhCode(50.0))); mock.queueRead(tBytes(25.0));
        s.read();
        mock.queueRead(frame(0x6666));
        s.readTemperature();
        assertEquals(List.of(0xE5, 0xE0, 0xE3), cmds(mock));
    }

    @Test
    void resolution() throws IOException {
        make();
        mock.setRegister(0xE7, 0x3A);
        s.setResolution(11, 11);
        assertTrue(lastWrite(mock, 0xE6, 0xBB));
        s.setResolution(8, 12);
        assertTrue(lastWrite(mock, 0xE6, 0x3B));
        s.setResolution(10, 13);
        assertTrue(lastWrite(mock, 0xE6, 0xBA));
        assertThrows(IllegalArgumentException.class, () -> s.setResolution(12, 12));
        mock.setRegister(0xE7, 0xBB);
        assertArrayEquals(new int[]{11, 11}, s.getResolution());
        mock.setRegister(0xE7, 0x3B);
        assertArrayEquals(new int[]{8, 12}, s.getResolution());
        mock.setRegister(0xE7, 0xBA);
        assertArrayEquals(new int[]{10, 13}, s.getResolution());
        mock.setRegister(0xE7, 0x3A);
        assertArrayEquals(new int[]{12, 14}, s.getResolution());
    }

    @Test
    void heaterVddAndUserRegister() throws IOException {
        make();
        mock.setRegister(0xE7, 0x3A);
        s.setHeater(true);
        assertTrue(lastWrite(mock, 0xE6, 0x3E));
        mock.setRegister(0xE7, 0x3E);
        assertTrue(s.isHeaterOn());
        s.setHeater(false);
        assertTrue(lastWrite(mock, 0xE6, 0x3A));
        mock.setRegister(0xE7, 0x3A);
        assertFalse(s.isHeaterOn());
        assertFalse(s.vddLow());
        mock.setRegister(0xE7, 0x7A);
        assertTrue(s.vddLow());
        mock.setRegister(0xE7, 0x3A);
        assertEquals(0x3A, s.readUserRegister());
        s.writeUserRegister(0x00);
        assertTrue(lastWrite(mock, 0xE6, 0x3A));
        assertEquals(0.017, s.conversionTime(), 1e-9);
    }

    @Test
    void resetAndIdentification() throws IOException {
        mock = new MockConnection();
        mock.setAddressWidth(2);
        mock.setRegister(0xFA0F, 0x11, 0, 0x22, 0, 0x33, 0, 0x44, 0);
        mock.setRegister(0xFCC9, 0x15, 0x66, 0, 0x77, 0x88, 0);
        mock.setRegister(0x84B8, 0xFF);
        s = new Si7021Full(mock);
        assertEquals(0x1122334415667788L, s.serialNumber());
        assertEquals(0x15, s.deviceType());
        assertEquals(0xFF, s.firmwareRevision());
        mock.writes().clear();
        s.softReset();
        assertEquals(List.of(0xFE), cmds(mock));
    }

    @Test
    void dewPoint() throws IOException {
        make();
        mock.queueRead(frame(rhCode(50.0)));
        mock.queueRead(tBytes(25.0));
        assertEquals(13.86, s.dewPoint(), 0.05);
    }
}
