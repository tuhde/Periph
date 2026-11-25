package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HS30xxTest {

    private static byte[] frame(int rh, int t, int status) {
        return new byte[]{(byte) ((status << 6) | (rh >> 8)), (byte) rh, (byte) (t >> 6), (byte) ((t & 0x3F) << 2)};
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    private static void assertWrites(MockConnection c, byte[]... expected) {
        List<byte[]> w = c.writes();
        assertEquals(expected.length, w.size());
        for (int i = 0; i < expected.length; i++) assertArrayEquals(expected[i], w.get(i), "write " + i);
    }

    private MockConnection mock;
    private HS30xxFull s;

    private void make() {
        mock = new MockConnection();
        s = new HS30xxFull(mock);
    }

    @Test
    void constructorDoesNoBusAccess() {
        make();
        assertTrue(mock.writes().isEmpty());
    }

    @Test
    void aliasesExtendTheDrivers() {
        MockConnection m = new MockConnection();
        assertTrue(new HS3001Full(m) instanceof HS30xxFull);
        assertTrue(new HS3002Full(m) instanceof HS30xxMinimal);
        assertTrue(new HS3003Minimal(m) instanceof HS30xxMinimal);
        assertTrue(new HS3001Minimal(m) instanceof HS30xxMinimal);
    }

    @Test
    void conversions() throws IOException {
        make();
        mock.queueRead(frame(0x2000, 0x1000, 0));
        double[] m = s.read();
        assertWrites(mock, new byte[0]);
        assertEquals(50.0, m[1], 0.01);
        assertEquals(1.25, m[0], 0.01);
        assertEquals(8192 / 16383.0 * 100.0, m[1], 1e-9);
        mock.queueRead(frame(0x3FFF, 0x3FFF, 0));
        m = s.read();
        assertEquals(100.0, m[1], 1e-6);
        assertEquals(125.0, m[0], 1e-6);
        mock.queueRead(frame(0, 0, 0));
        m = s.read();
        assertEquals(0.0, m[1], 1e-9);
        assertEquals(-40.0, m[0], 1e-9);
    }

    @Test
    void masksUndeterminedBits() throws IOException {
        make();
        mock.queueRead(b(0x20, 0x00, 0x10, 0x03));
        assertEquals(0x400 / 16383.0 * 165.0 - 40.0, s.read()[0], 1e-9);
    }

    @Test
    void staleRetriesOnceThenFails() throws IOException {
        make();
        mock.queueRead(frame(0x2000, 0x1000, 1));
        mock.queueRead(frame(0x2000, 0x1000, 0));
        assertEquals(50.0, s.read()[1], 0.01);
        assertEquals(1, mock.writes().size());
        mock.queueRead(frame(0, 0, 1));
        mock.queueRead(frame(0, 0, 1));
        assertThrows(IOException.class, () -> s.read());
    }

    @Test
    void busErrorOnRequest() {
        make();
        mock.failNextWrites(1, 0);
        assertThrows(IOException.class, () -> s.read());
    }

    @Test
    void singleValuesAndRaw() throws IOException {
        make();
        mock.queueRead(frame(0x2000, 0x1000, 0));
        assertEquals(1.25, s.readTemperature(), 0.01);
        mock.queueRead(b(0x20, 0x00));
        assertEquals(50.0, s.readHumidity(), 0.01);
        mock.queueRead(frame(0x1234, 0x0ABC, 0));
        assertArrayEquals(new int[]{0x1234, 0x0ABC, 0}, s.readRaw());
        mock.queueRead(frame(0x1234, 0x0ABC, 1));
        assertEquals(1, s.readRaw()[2]);
        assertThrows(IllegalArgumentException.class, () -> s.setMeasurementWait(0));
        s.setMeasurementWait(5);
        mock.queueRead(frame(0x2000, 0x1000, 0));
        assertEquals(50.0, s.readHumidity(), 0.01);
    }

    @Test
    void programmingCommandsAndNvmRead() throws IOException {
        make();
        s.enterProgrammingMode();
        s.exitProgrammingMode();
        assertWrites(mock, b(0xA0, 0, 0), b(0x80, 0, 0));
        make();
        mock.queueRead(b(0x81, 0x12, 0x34));
        assertEquals(0x1234, s.readNvm(0x1E));
        assertWrites(mock, b(0x1E, 0, 0));
        mock.queueRead(b(0x80, 0x12, 0x34));
        assertThrows(IOException.class, () -> s.readNvm(0x1E));
    }

    @Test
    void nvmMethodsNeedPowerCycle() {
        make();
        assertThrows(IllegalStateException.class, () -> s.getResolution(null));
        assertThrows(IllegalStateException.class, () -> s.setResolution(12, 12, null));
        assertThrows(IllegalStateException.class, () -> s.readId(null));
        assertTrue(mock.writes().isEmpty());
    }

    @Test
    void getResolution() throws IOException {
        make();
        mock.queueRead(b(0x81, 0x0C, 0x00));
        mock.queueRead(b(0x81, 0x08, 0x05));
        int[] cycles = {0};
        assertArrayEquals(new int[]{14, 12}, s.getResolution(() -> cycles[0]++));
        assertEquals(1, cycles[0]);
        assertWrites(mock, b(0xA0, 0, 0), b(0x06, 0, 0), b(0x11, 0, 0), b(0x80, 0, 0));
    }

    @Test
    void setResolutionPreservesOtherBits() throws IOException {
        make();
        mock.queueRead(b(0x81, 0x0C, 0x07));
        mock.queueRead(b(0x81, 0x0C, 0x21));
        s.setResolution(8, 12, () -> {});
        assertWrites(mock, b(0xA0, 0, 0), b(0x06, 0, 0), b(0x46, 0x00, 0x07),
                b(0x11, 0, 0), b(0x51, 0x08, 0x21), b(0x80, 0, 0));
    }

    @Test
    void setResolutionOneChannelAndInvalid() throws IOException {
        make();
        mock.queueRead(b(0x81, 0x00, 0x00));
        s.setResolution(null, 10, () -> {});
        assertWrites(mock, b(0xA0, 0, 0), b(0x11, 0, 0), b(0x51, 0x04, 0x00), b(0x80, 0, 0));
        make();
        assertThrows(IllegalArgumentException.class, () -> s.setResolution(9, null, () -> {}));
        assertTrue(mock.writes().isEmpty());
    }

    @Test
    void failedNvmReadStillLeavesProgrammingMode() {
        make();
        mock.queueRead(b(0x00, 0, 0));
        assertThrows(IOException.class, () -> s.getResolution(() -> {}));
        assertArrayEquals(b(0x80, 0, 0), mock.writes().get(mock.writes().size() - 1));
    }

    @Test
    void readId() throws IOException {
        make();
        mock.queueRead(b(0x81, 0xDE, 0xAD));
        mock.queueRead(b(0x81, 0xBE, 0xEF));
        assertEquals(0xDEADBEEFL, s.readId(() -> {}));
    }

    @Test
    void dewPoint() throws IOException {
        make();
        mock.queueRead(frame(8191, 6453, 0));
        assertEquals(13.86, s.dewPoint(), 0.1);
        mock.queueRead(frame(0, 0x1000, 0));
        assertEquals(-273.15, s.dewPoint(), 1e-9);
    }
}
