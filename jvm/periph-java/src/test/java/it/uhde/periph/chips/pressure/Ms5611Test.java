package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Ms5611Test {

    private static final int[] COEFFS = {40127, 36924, 23317, 23282, 33464, 28312};
    private static final int D1 = 9085466;
    private static final int D2 = 8569150;

    private static int[] prom(boolean corrupt) {
        int[] w = {0x3132, COEFFS[0], COEFFS[1], COEFFS[2], COEFFS[3], COEFFS[4], COEFFS[5], 0x5A3 << 4};
        w[7] |= Ms5611Minimal.crc4(w);
        if (corrupt) w[3] ^= 0x0100;
        return w;
    }

    private static void queueProm(MockConnection c, boolean corrupt) {
        for (int x : prom(corrupt)) c.queueRead(new byte[]{(byte) (x >> 8), (byte) x});
    }

    private static void queueAdc(MockConnection c, int v) {
        c.queueRead(new byte[]{(byte) (v >> 16), (byte) (v >> 8), (byte) v});
    }

    private static List<Integer> cmds(MockConnection c) {
        List<Integer> out = new ArrayList<>();
        for (byte[] w : c.writes()) out.add(w[0] & 0xFF);
        return out;
    }

    private MockConnection mock;
    private Ms5611Full s;

    private void make() {
        mock = new MockConnection();
        s = new Ms5611Full(mock);
    }

    private void ready() throws IOException {
        make();
        queueProm(mock, false);
        s.init();
        mock.writes().clear();
    }

    @Test
    void crc4Behaviour() {
        assertEquals(0, Ms5611Minimal.crc4(new int[8]));
        int[] p = prom(false);
        assertEquals(p[7] & 0xF, Ms5611Minimal.crc4(p));
        assertNotEquals(prom(true)[7] & 0xF, Ms5611Minimal.crc4(prom(true)));
    }

    @Test
    void initSequenceAndProm() throws IOException {
        make();
        assertTrue(mock.writes().isEmpty());
        queueProm(mock, false);
        s.init();
        assertEquals(List.of(0x1E, 0xA0, 0xA2, 0xA4, 0xA6, 0xA8, 0xAA, 0xAC, 0xAE), cmds(mock));
        assertArrayEquals(COEFFS, s.coefficients());
        assertTrue(s.promCrcOk());
        assertEquals(0x5A3, s.serialCode());
        assertNotNull(new Ms5611Minimal(new MockConnection()));
    }

    @Test
    void initRejectsBadCrcAndPropagatesBusError() {
        make();
        queueProm(mock, true);
        assertThrows(IOException.class, () -> s.init());
        make();
        mock.failNextWrites(1, 0);
        assertThrows(IOException.class, () -> s.init());
    }

    @Test
    void datasheetExampleAndSecondOrder() throws IOException {
        ready();
        double[] r = s.compensate(D1, D2);
        assertEquals(20.07, r[0], 1e-6);
        assertEquals(100009.0, r[1], 0.5);
        r = s.compensate(D1, D2 - 300000);
        assertEquals(9.55, r[0], 1e-6);
        assertEquals(97972.0, r[1], 0.5);
        r = s.compensate(D1, D2 - 300000, false);
        assertEquals(9.96, r[0], 1e-6);
        assertEquals(98061.0, r[1], 0.5);
        r = s.compensate(D1, D2 - 1200000);
        assertEquals(-27.09, r[0], 1e-6);
        assertEquals(90615.0, r[1], 0.5);
        r = s.compensate(D1, D2 - 1200000, false);
        assertEquals(-20.42, r[0], 1e-6);
        assertEquals(92219.0, r[1], 0.5);
    }

    @Test
    void minimalReadCommands() throws IOException {
        make();
        queueProm(mock, false);
        Ms5611Minimal m = new Ms5611Minimal(mock);
        m.init();
        mock.writes().clear();
        queueAdc(mock, D2);
        queueAdc(mock, D1);
        double[] r = m.read();
        assertEquals(List.of(0x58, 0x00, 0x48, 0x00), cmds(mock));
        assertEquals(20.07, r[0], 1e-6);
        assertEquals(100009.0, r[1], 0.5);
        queueAdc(mock, 0);
        assertThrows(IOException.class, m::read);
    }

    @Test
    void singleValuesAndRaw() throws IOException {
        ready();
        queueAdc(mock, D2);
        queueAdc(mock, D1);
        assertEquals(100009.0, s.readPressure(), 0.5);
        mock.writes().clear();
        queueAdc(mock, D2);
        assertEquals(20.07, s.readTemperature(), 1e-6);
        assertEquals(List.of(0x58, 0x00), cmds(mock));
        queueAdc(mock, D2);
        queueAdc(mock, D1);
        assertArrayEquals(new int[]{D1, D2}, s.readRaw());
    }

    @Test
    void oversamplingCommands() throws IOException {
        int[] osrs = {256, 512, 1024, 2048, 4096};
        for (int i = 0; i < 5; i++) {
            ready();
            s.setOversampling(osrs[i]);
            assertEquals(osrs[i], s.getOversampling());
            queueAdc(mock, D2);
            queueAdc(mock, D1);
            s.read();
            assertEquals(0x50 | (i << 1), cmds(mock).get(0));
            assertEquals(0x40 | (i << 1), cmds(mock).get(2));
        }
        make();
        assertThrows(IllegalArgumentException.class, () -> s.setOversampling(300));
        assertEquals(4096, s.getOversampling());
    }

    @Test
    void conversionTimes() {
        make();
        assertEquals(0.0006, s.conversionTime(256), 1e-9);
        assertEquals(0.00117, s.conversionTime(512), 1e-9);
        assertEquals(0.00228, s.conversionTime(1024), 1e-9);
        assertEquals(0.00454, s.conversionTime(2048), 1e-9);
        assertEquals(0.00904, s.conversionTime(4096), 1e-9);
        assertEquals(0.00904, s.conversionTime(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> s.conversionTime(100));
    }

    @Test
    void nonBlockingConversion() throws IOException {
        ready();
        s.startConversion(Ms5611Full.TEMPERATURE);
        s.startConversion(Ms5611Full.PRESSURE, 512);
        assertEquals(List.of(0x58, 0x42), cmds(mock));
        assertThrows(IllegalArgumentException.class, () -> s.startConversion(0, 99));
        queueAdc(mock, D1);
        assertEquals(D1, s.readAdc());
        queueAdc(mock, 0);
        assertThrows(IOException.class, () -> s.readAdc());
    }

    @Test
    void resetAndReadProm() throws IOException {
        ready();
        queueProm(mock, false);
        s.reset();
        assertEquals(0x1E, cmds(mock).get(0));
        assertEquals(9, mock.writes().size());
        queueProm(mock, false);
        assertArrayEquals(prom(false), s.readProm());
    }

    @Test
    void altitudeFormula() throws IOException {
        ready();
        queueAdc(mock, D2);
        queueAdc(mock, D1);
        double expected = 44330.0 * (1.0 - Math.pow(100009.0 / 101325.0, 1.0 / 5.255));
        double h = s.altitude();
        assertEquals(expected, h, 1e-6);
        assertTrue(h > 0);
        queueAdc(mock, D2);
        queueAdc(mock, D1);
        assertEquals(0.0, s.altitude(100009.0), 1e-6);
    }
}
