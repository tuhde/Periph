package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HDC1080Test {

    private MockConnection mock;
    private HDC1080Full s;

    private void make() {
        mock = new MockConnection();
        s = new HDC1080Full(mock);
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    private void assertLast(int... expected) {
        assertArrayEquals(b(expected), mock.writes().get(mock.writes().size() - 1));
    }

    @Test
    void constructorDoesNoBusAccessAndInitWritesDefault() throws IOException {
        make();
        assertTrue(mock.writes().isEmpty());
        s.init();
        assertLast(0x02, 0x10, 0x00);
        assertNotNull(new HDC1080Minimal(new MockConnection()));
    }

    @Test
    void readSequence() throws IOException {
        make();
        mock.queueRead(b(0x66, 0x66, 0x80, 0x00));
        double[] v = s.read();
        assertEquals(1, mock.writes().size());
        assertLast(0x00);
        assertEquals(0x6666 / 65536.0 * 165.0 - 40.0, v[0], 1e-3);
        assertEquals(50.0, v[1], 1e-3);
    }

    @Test
    void readPollsWhileNackedAndTimesOut() throws IOException {
        make();
        mock.failNextReads(3);
        mock.queueRead(b(0x66, 0x66, 0x80, 0x00));
        assertEquals(50.0, s.read()[1], 1e-3);
        make();
        mock.failNextReads(1000);
        assertThrows(IOException.class, () -> s.read());
    }

    @Test
    void singleMeasurementsSwitchMode() throws IOException {
        make();
        mock.queueRead(b(0x80, 0x00));
        assertEquals(42.5, s.readTemperature(), 1e-3);
        assertEquals(2, mock.writes().size());
        assertArrayEquals(b(0x02, 0x00, 0x00), mock.writes().get(0));
        mock.queueRead(b(0x80, 0x00));
        assertEquals(50.0, s.readHumidity(), 1e-3);
        assertLast(0x01);
    }

    @Test
    void readRaw() throws IOException {
        make();
        mock.queueRead(b(0x12, 0x34, 0x56, 0x78));
        assertArrayEquals(new int[]{0x1234, 0x5678}, s.readRaw());
    }

    @Test
    void resolutionAndConversionTime() throws IOException {
        make();
        s.setResolution(11, 8);
        assertLast(0x02, 0x16, 0x00);
        assertEquals(0.00365 + 0.0025, s.conversionTime(), 1e-9);
        s.setResolution(14, 14);
        assertLast(0x02, 0x10, 0x00);
        assertEquals(0.01285, s.conversionTime(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> s.setResolution(8, 14));
        assertThrows(IllegalArgumentException.class, () -> s.setResolution(14, 12));
    }

    @Test
    void heaterBatteryConfig() throws IOException {
        make();
        s.setHeater(true);
        assertLast(0x02, 0x30, 0x00);
        s.setHeater(false);
        assertLast(0x02, 0x10, 0x00);
        mock.queueRead(b(0x30, 0x00));
        assertTrue(s.isHeaterOn());
        mock.queueRead(b(0x18, 0x00));
        assertTrue(s.batteryLow());
        mock.queueRead(b(0x10, 0x00));
        assertFalse(s.batteryLow());
        mock.queueRead(b(0x10, 0x00));
        assertEquals(0x1000, s.readConfig());
    }

    @Test
    void softwareResetRewritesConfig() throws IOException {
        make();
        s.setHeater(true);
        s.softwareReset();
        List<byte[]> w = mock.writes();
        assertArrayEquals(b(0x02, 0x80, 0x00), w.get(w.size() - 2));
        assertArrayEquals(b(0x02, 0x30, 0x00), w.get(w.size() - 1));
    }

    @Test
    void serialId() throws IOException {
        make();
        mock.queueRead(b(0x00, 0x12));
        mock.queueRead(b(0x34, 0x56));
        mock.queueRead(b(0x80, 0x00));
        assertEquals((0x12L << 24) | (0x3456L << 8) | 0x100, s.serialId());
    }

    @Test
    void ids() throws IOException {
        make();
        mock.queueRead(b(0x54, 0x49));
        assertEquals(0x5449, s.manufacturerId());
        mock.queueRead(b(0x10, 0x50));
        assertEquals(0x1050, s.deviceId());
    }
}
