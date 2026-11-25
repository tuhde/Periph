package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HTU21DFTest {

    private static byte[] frame(int code) {
        byte[] f = new byte[3];
        f[0] = (byte) (code >> 8); f[1] = (byte) code;
        f[2] = (byte) HTU21DFMinimal.crc8(f, 0, 2);
        return f;
    }

    private static int rhCode(double rh) { return ((int) Math.round((rh + 6.0) * 65536.0 / 125.0) & 0xFFFC) | 0x02; }
    private static int tCode(double t) { return (int) Math.round((t + 46.85) * 65536.0 / 175.72) & 0xFFFC; }

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
    private HTU21DFFull s;

    private void make() {
        mock = new MockConnection();
        s = new HTU21DFFull(mock);
    }

    @Test
    void crcReference() {
        assertEquals(0x7C, HTU21DFMinimal.crc8(new byte[]{0x68, 0x3A}, 0, 2));
        assertEquals(0x6B, HTU21DFMinimal.crc8(new byte[]{0x4E, (byte) 0x85}, 0, 2));
        assertEquals(0x79, HTU21DFMinimal.crc8(new byte[]{(byte) 0xDC}, 0, 1));
    }

    @Test
    void constructorDoesNoBusAccessAndInitResets() throws IOException {
        make();
        assertTrue(mock.writes().isEmpty());
        s.init();
        assertEquals(List.of(0xFE), cmds(mock));
        assertNotNull(new HTU21DFMinimal(new MockConnection()));
    }

    @Test
    void readUsesF3ThenF5() throws IOException {
        make();
        mock.queueRead(frame(tCode(25.0)));
        mock.queueRead(frame(rhCode(50.0)));
        double[] v = s.read();
        assertEquals(List.of(0xF3, 0xF5), cmds(mock));
        assertEquals(25.0, v[0], 0.05);
        assertEquals(50.0, v[1], 0.05);
    }

    @Test
    void datasheetConversion() throws IOException {
        make();
        mock.queueRead(frame(0x683A)); mock.queueRead(frame(0x683A));
        double[] v = s.read();
        assertEquals(-46.85 + 175.72 * 0x6838 / 65536.0, v[0], 1e-6);
        assertEquals(-6.0 + 125.0 * 0x6838 / 65536.0, v[1], 1e-6);
    }

    @Test
    void readPollsWhileNacked() throws IOException {
        make();
        mock.failNextReads(3);
        mock.queueRead(frame(tCode(20.0)));
        mock.queueRead(frame(rhCode(40.0)));
        assertEquals(40.0, s.read()[1], 0.05);
    }

    @Test
    void timeoutWhenNeverReady() {
        make();
        mock.failNextReads(1000);
        assertThrows(IOException.class, () -> s.read());
    }

    @Test
    void crcMismatchRetriedOnceThenThrows() throws IOException {
        make();
        byte[] bad = frame(0x6000);
        bad[2] ^= (byte) 0xFF;
        mock.queueRead(bad); mock.queueRead(bad);
        assertThrows(IOException.class, () -> s.read());
        make();
        mock.queueRead(bad); mock.queueRead(frame(tCode(25.0))); mock.queueRead(frame(rhCode(50.0)));
        assertEquals(25.0, s.read()[0], 0.05);
        assertEquals(List.of(0xF3, 0xF3, 0xF5), cmds(mock));
    }

    @Test
    void humidityIsNotClamped() throws IOException {
        make();
        mock.queueRead(frame(0)); mock.queueRead(frame(0));
        assertEquals(-6.0, s.read()[1], 1e-9);
    }

    @Test
    void singleValuesRawAndCompensated() throws IOException {
        make();
        mock.queueRead(frame(rhCode(30.0)));
        assertEquals(30.0, s.readHumidity(), 0.05);
        mock.queueRead(frame(tCode(-10.0)));
        assertEquals(-10.0, s.readTemperature(), 0.05);
        assertEquals(List.of(0xF5, 0xF3), cmds(mock));
        mock.queueRead(frame(0x1237)); mock.queueRead(frame(0x4321));
        assertArrayEquals(new int[]{0x1234, 0x4320}, s.readRaw());
        mock.queueRead(frame(tCode(35.0))); mock.queueRead(frame(rhCode(50.0)));
        assertEquals(51.5, s.readHumidityCompensated(), 0.1);
    }

    @Test
    void holdMasterCommands() throws IOException {
        make();
        s.setHoldMaster(true);
        mock.queueRead(frame(tCode(25.0))); mock.queueRead(frame(rhCode(50.0)));
        s.read();
        assertEquals(List.of(0xE3, 0xE5), cmds(mock));
    }

    @Test
    void resolution() throws IOException {
        make();
        mock.setRegister(0xE7, 0x02);
        s.setResolution(3);
        assertTrue(lastWrite(mock, 0xE6, 0x83));
        s.setResolution(1);
        assertTrue(lastWrite(mock, 0xE6, 0x03));
        s.setResolution(2);
        assertTrue(lastWrite(mock, 0xE6, 0x82));
        assertThrows(IllegalArgumentException.class, () -> s.setResolution(4));
        int[][] cases = {{0x83, 3}, {0x03, 1}, {0x82, 2}, {0x02, 0}};
        for (int[] c : cases) {
            mock.setRegister(0xE7, c[0]);
            assertEquals(c[1], s.getResolution());
        }
    }

    @Test
    void heaterBatteryAndUserRegister() throws IOException {
        make();
        mock.setRegister(0xE7, 0x02);
        s.setHeater(true);
        assertTrue(lastWrite(mock, 0xE6, 0x06));
        mock.setRegister(0xE7, 0x06);
        assertTrue(s.heater());
        s.setHeater(false);
        assertTrue(lastWrite(mock, 0xE6, 0x02));
        mock.setRegister(0xE7, 0x02);
        assertFalse(s.heater());
        assertFalse(s.batteryLow());
        mock.setRegister(0xE7, 0x42);
        assertTrue(s.batteryLow());
        assertEquals(0x42, s.readUserRegister());
    }

    @Test
    void softResetAndDewPoint() throws IOException {
        make();
        s.softReset();
        assertEquals(List.of(0xFE), cmds(mock));
        mock.queueRead(frame(tCode(25.0))); mock.queueRead(frame(rhCode(50.0)));
        assertEquals(13.86, s.dewPoint(), 0.1);
    }
}
