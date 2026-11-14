package it.uhde.periph.chips.environmental;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AM2315Test {

    private static byte[] resp(int... body) {
        byte[] f = new byte[body.length + 2];
        for (int i = 0; i < body.length; i++) f[i] = (byte) body[i];
        int crc = AM2315Minimal.crc16(f, 0, body.length);
        f[body.length] = (byte) crc;
        f[body.length + 1] = (byte) (crc >> 8);
        return f;
    }

    private static byte[] readResp(int... data) {
        int[] body = new int[data.length + 2];
        body[0] = 0x03;
        body[1] = data.length;
        System.arraycopy(data, 0, body, 2, data.length);
        return resp(body);
    }

    private MockConnection mock;
    private AM2315Full s;

    private void make() {
        mock = new MockConnection();
        s = new AM2315Full(mock);
        s.setMinIntervalMs(0);       // tests do not wait the 2 s between transactions
    }

    private byte[] lastWrite() {
        return mock.writes().get(mock.writes().size() - 1);
    }

    @Test
    void crcReference() {
        byte[] a = {0x03, 0x04, 0x01, (byte) 0xF4, 0x00, (byte) 0xFA};
        byte[] b = {0x03, 0x04, 0x03, 0x39, 0x01, 0x15};
        byte[] w = {0x10, 0x10, 0x02, 0x01, 0x02};
        assertEquals(0xA531, AM2315Minimal.crc16(a, 0, 6));
        assertEquals(0xFEE1, AM2315Minimal.crc16(b, 0, 6));
        assertEquals(0x92C0, AM2315Minimal.crc16(w, 0, 5));
    }

    @Test
    void constructorDoesNoBusAccess() {
        make();
        assertTrue(mock.writes().isEmpty());
        assertNotNull(new AM2315Minimal(new MockConnection()));
    }

    @Test
    void readWakesThenCommandsThenDecodes() throws IOException {
        make();
        mock.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA));
        double[] v = s.read();
        assertEquals(2, mock.writes().size());
        assertArrayEquals(new byte[]{0x00}, mock.writes().get(0));
        assertArrayEquals(new byte[]{0x03, 0x00, 0x04}, mock.writes().get(1));
        assertEquals(25.0, v[0], 1e-9);
        assertEquals(50.0, v[1], 1e-9);
    }

    @Test
    void datasheetFrame() throws IOException {
        make();
        mock.queueRead(new byte[]{0x03, 0x04, 0x03, 0x39, 0x01, 0x15, (byte) 0xE1, (byte) 0xFE});
        double[] v = s.read();
        assertEquals(27.7, v[0], 1e-9);
        assertEquals(82.5, v[1], 1e-9);
    }

    @Test
    void negativeTemperatureIsSignMagnitude() throws IOException {
        make();
        mock.queueRead(readResp(0x01, 0xF4, 0x80, 0x65));
        assertEquals(-10.1, s.read()[0], 1e-9);
    }

    @Test
    void wakeNackIsIgnored() throws IOException {
        make();
        mock.failNextWrites(1, 0);
        mock.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA));
        assertEquals(50.0, s.read()[1], 1e-9);
    }

    @Test
    void crcRetryRecoversThenFails() throws IOException {
        make();
        byte[] bad = readResp(0x01, 0xF4, 0x00, 0xFA);
        bad[bad.length - 1] ^= 0xFF;
        mock.queueRead(bad);
        mock.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA));
        assertEquals(50.0, s.read()[1], 1e-9);
        assertEquals(4, mock.writes().size());     // wake, command, wake, command
        make();
        mock.queueRead(bad);
        mock.queueRead(bad);
        assertThrows(IOException.class, s::read);
    }

    @Test
    void busErrorIsRetriedOnce() throws IOException {
        make();
        mock.failNextReads(1);
        mock.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA));
        assertEquals(25.0, s.read()[0], 1e-9);
        make();
        mock.failNextReads(2);
        assertThrows(IOException.class, s::read);
    }

    @Test
    void errorResponseIsNotRetried() {
        make();
        mock.queueRead(new byte[]{(byte) 0x83, 0, 0, 0});
        AM2315Minimal.ErrorResponse e = assertThrows(AM2315Minimal.ErrorResponse.class, s::read);
        assertEquals(0x83, e.code());
        assertEquals(2, mock.writes().size());
    }

    @Test
    void minimumIntervalIsEnforced() throws IOException {
        MockConnection c = new MockConnection();
        AM2315Minimal m = new AM2315Minimal(c);
        m.setMinIntervalMs(50);
        c.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA));
        long t0 = System.nanoTime();
        m.read();
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 40);
    }

    @Test
    void singleValuesAndRaw() throws IOException {
        make();
        mock.queueRead(readResp(0x00, 0x7B));
        assertEquals(12.3, s.readHumidity(), 1e-9);
        assertArrayEquals(new byte[]{0x03, 0x00, 0x02}, lastWrite());
        mock.queueRead(readResp(0x80, 0x65));
        assertEquals(-10.1, s.readTemperature(), 1e-9);
        assertArrayEquals(new byte[]{0x03, 0x02, 0x02}, lastWrite());
        mock.queueRead(readResp(0x01, 0xF4, 0x80, 0x65));
        assertArrayEquals(new int[]{0x01F4, 0x8065}, s.readRaw());
    }

    @Test
    void readFreshReturnsSecond() throws IOException {
        make();
        mock.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA));
        mock.queueRead(readResp(0x01, 0xF9, 0x01, 0x00));
        double[] v = s.readFresh();
        assertEquals(25.6, v[0], 1e-9);
        assertEquals(50.5, v[1], 1e-9);
    }

    @Test
    void registerReadAndLimits() throws IOException {
        make();
        mock.queueRead(readResp(1, 2, 3));
        assertArrayEquals(new byte[]{1, 2, 3}, s.readRegisters(0x08, 3));
        assertThrows(IllegalArgumentException.class, () -> s.readRegisters(0, 0));
        assertThrows(IllegalArgumentException.class, () -> s.readRegisters(0, 11));
    }

    @Test
    void deviceInfo() throws IOException {
        make();
        mock.queueRead(readResp(0x09, 0x0F, 0x10, 0x12, 0x34, 0x56, 0x78));
        assertArrayEquals(new long[]{0x090F, 0x10, 0x12345678L}, s.readDeviceInfo());
        assertArrayEquals(new byte[]{0x03, 0x08, 0x07}, lastWrite());
    }

    @Test
    void userRegisters() throws IOException {
        make();
        mock.queueRead(readResp(0x12, 0x34, 0xAB, 0xCD));
        assertArrayEquals(new int[]{0x1234, 0xABCD}, s.readUserRegisters());
        mock.queueRead(resp(0x10, 0x10, 0x04));
        s.writeUserRegisters(0x1234, 0xABCD);
        assertArrayEquals(resp(0x10, 0x10, 0x04, 0x12, 0x34, 0xAB, 0xCD), lastWrite());
    }

    @Test
    void writeRegistersFrameAndLimits() throws IOException {
        make();
        mock.queueRead(resp(0x10, 0x10, 0x02));
        s.writeRegisters(0x10, new byte[]{1, 2});
        assertArrayEquals(new byte[]{0x10, 0x10, 0x02, 1, 2, (byte) 0xC0, (byte) 0x92}, lastWrite());
        assertThrows(IllegalArgumentException.class, () -> s.writeRegisters(0x0F, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> s.writeRegisters(0x13, new byte[]{1, 2}));
        assertThrows(IllegalArgumentException.class, () -> s.writeRegisters(0x10, new byte[0]));
        mock.queueRead(new byte[]{(byte) 0x82, 0, 0, 0, 0});
        assertThrows(AM2315Minimal.ErrorResponse.class, () -> s.writeRegisters(0x10, new byte[]{1}));
    }

    @Test
    void wake() {
        make();
        s.wake();
        assertEquals(1, mock.writes().size());
        assertArrayEquals(new byte[]{0x00}, mock.writes().get(0));
    }
}
