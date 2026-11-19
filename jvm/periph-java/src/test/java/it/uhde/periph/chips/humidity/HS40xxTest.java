package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HS40xxTest {

    private static int crc8(int... d) {
        int crc = 0xFF;
        for (int b : d) {
            crc ^= b;
            for (int i = 0; i < 8; i++) crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x1D) & 0xFF : (crc << 1) & 0xFF;
        }
        return crc;
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    private static byte[] ht(int h, int t) {
        int[] d = {h >> 8, h & 0xFF, t >> 8, t & 0xFF};
        return b(d[0], d[1], d[2], d[3], crc8(d));
    }

    private static byte[] tOnly(int t) {
        return b(t >> 8, t & 0xFF, crc8(0, 0, t >> 8, t & 0xFF));
    }

    private static byte[] withStatus(int status, byte[] frame) {
        byte[] out = new byte[frame.length + 1];
        out[0] = (byte) status;
        System.arraycopy(frame, 0, out, 1, frame.length);
        return out;
    }

    private static void assertWrites(MockConnection c, byte[]... expected) {
        List<byte[]> w = c.writes();
        assertEquals(expected.length, w.size());
        for (int i = 0; i < expected.length; i++) assertArrayEquals(expected[i], w.get(i), "write " + i);
    }

    private MockConnection mock;
    private HS40xxFull s;

    private void make() {
        mock = new MockConnection();
        s = new HS40xxFull(mock);
    }

    @Test
    void crcDatasheetExample() {
        assertEquals(0xF2, crc8(0x05, 0x80, 0x05, 0x80));
    }

    @Test
    void constructorDoesNoBusAccess() {
        make();
        assertTrue(mock.writes().isEmpty());
    }

    @Test
    void aliasesExtendTheDrivers() {
        MockConnection m = new MockConnection();
        assertTrue(new HS4001Full(m) instanceof HS40xxFull);
        assertTrue(new HS4003Full(m) instanceof HS40xxMinimal);
        assertTrue(new HS4003Minimal(m) instanceof HS40xxMinimal);
        assertTrue(new HS4001Minimal(m) instanceof HS40xxMinimal);
    }

    @Test
    void datasheetExampleFrame() throws IOException {
        make();
        mock.queueRead(b(0x05, 0x80, 0x05, 0x80, 0xF2));
        double[] m = s.read();
        assertWrites(mock, b(0xF5));
        assertEquals(8.59, m[1], 0.01);
        assertEquals(-25.8, m[0], 0.05);
    }

    @Test
    void fullScaleAndZero() throws IOException {
        make();
        mock.queueRead(ht(0x3FFF, 0x3FFF));
        double[] m = s.read();
        assertEquals(100.0, m[1], 1e-6);
        assertEquals(125.0, m[0], 1e-6);
        mock.queueRead(ht(0, 0));
        m = s.read();
        assertEquals(0.0, m[1], 1e-9);
        assertEquals(-40.0, m[0], 1e-9);
    }

    @Test
    void nackIsRetriedAndPersistentNackFails() throws IOException {
        make();
        mock.failNextReads(2);
        mock.queueRead(ht(0x2000, 0x1000));
        assertEquals(50.0, s.read()[1], 0.01);
        mock.failNextReads(1000);
        assertThrows(IOException.class, () -> s.read());
    }

    @Test
    void crcMismatchRetriedOnce() throws IOException {
        make();
        byte[] bad = ht(0x2000, 0x1000);
        bad[4] ^= (byte) 0xFF;
        mock.queueRead(bad);
        mock.queueRead(ht(0x2000, 0x1000));
        assertEquals(50.0, s.read()[1], 0.01);
        assertWrites(mock, b(0xF5), b(0xF5));
        make();
        mock.queueRead(bad);
        mock.queueRead(bad);
        assertThrows(IOException.class, () -> s.read());
    }

    @Test
    void singleValuesHoldAndRaw() throws IOException {
        make();
        mock.queueRead(tOnly(0x1000));
        assertEquals(4096 / 16383.0 * 165.0 - 40.0, s.readTemperature(), 1e-9);
        mock.queueRead(ht(0x2000, 0x1000));
        assertEquals(50.0, s.readHumidity(), 0.01);
        mock.queueRead(ht(0x2000, 0x1000));
        assertEquals(50.0, s.readHold()[1], 0.01);
        mock.queueRead(ht(0x1234, 0x0ABC));
        assertArrayEquals(new int[]{0x1234, 0x0ABC}, s.readRaw());
        assertWrites(mock, b(0xF3), b(0xF5), b(0xE5), b(0xF5));
    }

    @Test
    void registers() throws IOException {
        make();
        mock.queueRead(b(0x5A));
        assertEquals(0x5A, s.readRegister(0x02));
        s.writeRegister(0x03, 0x0A);
        assertWrites(mock, b(0xA7, 0x02), b(0xA6, 0x03, 0x0A));
    }

    @Test
    void resolution() throws IOException {
        make();
        mock.queueRead(b(0xF3));
        s.setResolution(8, 12);
        assertWrites(mock, b(0xA7, 0x00), b(0xA6, 0x00, 0xF2));
        make();
        assertThrows(IllegalArgumentException.class, () -> s.setResolution(9, null));
        assertTrue(mock.writes().isEmpty());
        mock.queueRead(b(0x0B));
        assertArrayEquals(new int[]{12, 14}, s.getResolution());
    }

    @Test
    void periodicWithoutAlerts() throws IOException {
        make();
        mock.queueRead(b(0x00));
        mock.queueRead(b(0x01));
        s.startPeriodic(2.5);
        assertWrites(mock, b(0xA7, 0x03), b(0xA7, 0x02), b(0xA6, 0x02, 0xA1));
        assertThrows(IllegalArgumentException.class, () -> s.startPeriodic(3));
        mock.queueRead(ht(0x2000, 0x1000));
        double[] p = s.readPeriodic();
        assertEquals(0, p[2]);
        assertEquals(50.0, p[1], 0.01);
        s.stopPeriodic();
        assertArrayEquals(b(0x30), mock.writes().get(mock.writes().size() - 1));
    }

    @Test
    void alertsAndStatus() throws IOException {
        make();
        assertThrows(IllegalStateException.class, () -> s.pollInterrupt());
        mock.queueRead(b(0x00));
        s.enableInterrupt(HS40xxFull.SOURCE_RH_HIGH);
        assertWrites(mock, b(0xA7, 0x03), b(0xA6, 0x03, 0x02));
        mock.queueRead(withStatus(0x0A, ht(0x2000, 0x1000)));
        assertEquals(0x0A, (int) s.readPeriodic()[2]);
        mock.queueRead(withStatus(0x02, ht(0x2000, 0x1000)));
        assertEquals(0x02, s.pollInterrupt());
        mock.queueRead(b(0x02));
        s.disableInterrupt(HS40xxFull.SOURCE_RH_HIGH);
        assertArrayEquals(b(0xA6, 0x03, 0x00), mock.writes().get(mock.writes().size() - 1));
    }

    @Test
    void thresholds() throws IOException {
        make();
        s.setAlertThresholds(60.0, 55.0, null, null, null, null, null, null);
        assertWrites(mock, b(0xA6, 0x0F, 0x66), b(0xA6, 0x10, 0x26), b(0xA6, 0x0D, 0x33), b(0xA6, 0x0E, 0x23));
        make();
        s.setAlertThresholds(null, null, null, null, 125.0, null, -40.0, null);
        assertWrites(mock, b(0xA6, 0x07, 0xFF), b(0xA6, 0x08, 0x3F), b(0xA6, 0x09, 0x00), b(0xA6, 0x0A, 0x00));
    }

    @Test
    void polarityIdAndReset() throws IOException {
        make();
        mock.queueRead(b(0xB1));
        s.setAlertPolarity(false);
        assertArrayEquals(b(0xA6, 0x02, 0xB0), mock.writes().get(1));
        mock.queueRead(b(0xDE, 0xAD, 0xBE, 0xEF));
        assertEquals(0xDEADBEEFL, s.readId());
        make();
        s.softReset();
        assertWrites(mock, b(0x30), b(0xFE));
    }

    @Test
    void dewPoint() throws IOException {
        make();
        mock.queueRead(ht((int) (0.5 * 16383), (int) ((25.0 + 40.0) / 165.0 * 16383)));
        assertEquals(13.86, s.dewPoint(), 0.1);
        mock.queueRead(ht(0, 0x1000));
        assertEquals(-273.15, s.dewPoint(), 1e-9);
    }
}
