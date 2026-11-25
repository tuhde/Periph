package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Htu31dTest {

    private static byte[] word(int code) {
        byte[] f = new byte[3];
        f[0] = (byte) (code >> 8); f[1] = (byte) code;
        f[2] = (byte) Htu31dMinimal.crc8(f, 0, 2);
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
        for (byte[] w : c.writes()) out.add(w[0] & 0xFF);
        return out;
    }

    private MockConnection mock;
    private Htu31dFull s;

    private void make() {
        mock = new MockConnection();
        s = new Htu31dFull(mock);
    }

    @Test
    void crcReference() {
        assertEquals(0x79, Htu31dMinimal.crc8(new byte[]{(byte) 0xDC}, 0, 1));
        assertEquals(0x7C, Htu31dMinimal.crc8(new byte[]{0x68, 0x3A}, 0, 2));
        assertEquals(0x6B, Htu31dMinimal.crc8(new byte[]{0x4E, (byte) 0x85}, 0, 2));
    }

    @Test
    void constructorDoesNoBusAccessAndInitResets() throws IOException {
        make();
        assertTrue(mock.writes().isEmpty());
        s.init();
        assertEquals(List.of(0x1E), cmds(mock));
        assertNotNull(new Htu31dMinimal(new MockConnection()));
    }

    @Test
    void readConvertsAndSendsCommands() throws IOException {
        make();
        mock.queueRead(trh(0x683A, 0x8000));
        double[] m = s.read();
        assertEquals(List.of(0x5E, 0x00), cmds(mock));
        assertEquals(-40.0 + 165.0 * 0x683A / 65535.0, m[0], 1e-6);
        assertEquals(100.0 * 0x8000 / 65535.0, m[1], 1e-6);
    }

    @Test
    void fullScaleAndClamp() throws IOException {
        make();
        mock.queueRead(trh(0xFFFF, 0xFFFF));
        double[] m = s.read();
        assertEquals(125.0, m[0], 1e-6);
        assertEquals(100.0, m[1], 1e-6);
        mock.queueRead(trh(0, 0));
        m = s.read();
        assertEquals(-40.0, m[0], 1e-6);
        assertEquals(0.0, m[1], 1e-6);
    }

    @Test
    void crcMismatchThrows() {
        make();
        byte[] bad = trh(0x683A, 0x8000);
        bad[2] ^= (byte) 0xFF;
        mock.queueRead(bad);
        assertThrows(IOException.class, () -> s.read());
        byte[] bad2 = trh(0x683A, 0x8000);
        bad2[5] ^= (byte) 0xFF;
        mock.queueRead(bad2);
        assertThrows(IOException.class, () -> s.read());
    }

    @Test
    void singleValuesAndRaw() throws IOException {
        make();
        mock.queueRead(word(0x4000));
        assertEquals(100.0 * 0x4000 / 65535.0, s.readHumidity(), 1e-6);
        assertEquals(List.of(0x5E, 0x10), cmds(mock));
        mock.writes().clear();
        mock.queueRead(word(0x4000));
        assertEquals(-40.0 + 165.0 * 0x4000 / 65535.0, s.readTemperature(), 1e-6);
        assertEquals(List.of(0x5E, 0x00), cmds(mock));
        mock.queueRead(trh(0x1234, 0x5678));
        assertArrayEquals(new int[]{0x1234, 0x5678}, s.readRaw());
    }

    @Test
    void resolution() throws IOException {
        make();
        assertArrayEquals(new int[]{3, 3}, s.getResolution());
        assertEquals(0.02032, s.conversionTime(), 1e-9);
        s.setResolution(2, 1);
        mock.queueRead(trh(0x4000, 0x4000));
        s.read();
        assertEquals(0x40 | (2 << 3) | (1 << 1), cmds(mock).get(0));
        assertThrows(IllegalArgumentException.class, () -> s.setResolution(4, 0));
        assertThrows(IllegalArgumentException.class, () -> s.setResolution(0, -1));
        mock.writes().clear();
        s.setResolution(1, 2);
        s.startConversion();
        assertEquals(List.of(0x40 | (1 << 3) | (2 << 1)), cmds(mock));
        assertEquals(0.00214 + 0.00603, s.conversionTime(), 1e-9);
    }

    @Test
    void readResultUsesReadCommandOnly() throws IOException {
        make();
        mock.queueRead(trh(0x2000, 0x6000));
        double[] m = s.readResult();
        assertEquals(List.of(0x00), cmds(mock));
        assertEquals(100.0 * 0x6000 / 65535.0, m[1], 1e-6);
    }

    @Test
    void heaterAndDiagnostic() throws IOException {
        make();
        s.setHeater(true);
        s.setHeater(false);
        assertEquals(List.of(0x04, 0x02), cmds(mock));
        mock.queueRead(new byte[]{0x01});
        assertTrue(s.isHeaterOn());
        mock.queueRead(new byte[]{(byte) 0xA5});
        assertEquals(0xA5, s.diagnostic());
        mock.queueRead(new byte[]{(byte) 0xA5});
        Map<String, Boolean> f = s.diagnosticFlags();
        assertTrue(f.get("nvm_err"));
        assertTrue(f.get("rh_high_err"));
        assertTrue(f.get("t_high_err"));
        assertTrue(f.get("heater_on"));
        assertFalse(f.get("rh_overrun"));
        assertFalse(f.get("t_low_err"));
    }

    @Test
    void serialNumber() throws IOException {
        make();
        byte[] d = {(byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 0};
        d[3] = (byte) Htu31dMinimal.crc8(d, 0, 3);
        mock.queueRead(d);
        assertEquals(0xABCDEF, s.serialNumber());
        byte[] bad = d.clone();
        bad[3] ^= 0x55;
        mock.queueRead(bad);
        assertThrows(IOException.class, () -> s.serialNumber());
    }

    @Test
    void softResetRestoresDefaults() throws IOException {
        make();
        s.setResolution(0, 1);
        s.softReset();
        assertEquals(List.of(0x1E), cmds(mock));
        assertArrayEquals(new int[]{3, 3}, s.getResolution());
    }

    @Test
    void dewPoint() throws IOException {
        make();
        mock.queueRead(trh((int) Math.round(65.0 * 65535.0 / 165.0), (int) Math.round(0.5 * 65535.0)));
        assertEquals(13.86, s.dewPoint(), 0.15);
    }
}
