package it.uhde.periph.chips.gas;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class Rrh62000Test {

    static final int[] WORDS = {1234, 1000, 500, 100, 20, 52, 125, 203, 61, 140, 230, 2345, 4567, 30, 812, 215, 0};

    static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    static int[] block(int status, int[] words) {
        byte[] r = new byte[37];
        r[0] = (byte) status;
        r[1] = (byte) (status >> 8);
        for (int i = 0; i < 17; i++) {
            r[2 + 2 * i] = (byte) (words[i] >> 8);
            r[3 + 2 * i] = (byte) words[i];
        }
        r[36] = (byte) Rrh62000Minimal.crc8(r, 36);
        int[] out = new int[37];
        for (int i = 0; i < 37; i++) out[i] = r[i] & 0xFF;
        return out;
    }

    static MockConnection mock() {
        MockConnection c = new MockConnection();
        c.setRegister(0x75, 2, 7);
        return c;
    }

    static Rrh62000Full full(MockConnection c) throws IOException {
        Rrh62000Full s = new Rrh62000Full(c, 0);
        s.init();
        return s;
    }

    static byte[] last(MockConnection c) { return c.writes().get(c.writes().size() - 1); }

    @Test
    void crc() {
        assertEquals(0x92, Rrh62000Minimal.crc8(b(0xBE, 0xEF), 2));
        assertEquals(0x81, Rrh62000Minimal.crc8(b(0, 0), 2));
    }

    @Test
    void initAndRead() throws IOException {
        MockConnection c = mock();
        Rrh62000Minimal s = new Rrh62000Minimal(c, 0);
        s.init();
        assertArrayEquals(b(0x75), last(c));
        c.setRegister(0x40, 1);
        c.setRegister(0x00, block(5, WORDS));
        Rrh62000Minimal.Measurement m = s.read();
        assertEquals(5.2, m.pm1UgM3(), 1e-9);
        assertEquals(12.5, m.pm2_5UgM3(), 1e-9);
        assertEquals(20.3, m.pm10UgM3(), 1e-9);
        assertEquals(23.45, m.temperatureC(), 1e-9);
        assertEquals(45.67, m.humidityRh(), 1e-9);
        assertEquals(300, m.tvoc());
        assertEquals(812, m.eco2Ppm());
        assertEquals(2.15, m.iaq(), 1e-9);
        int n = c.writes().size();
        assertArrayEquals(b(0x40), c.writes().get(n - 2));
        assertArrayEquals(b(0x00), c.writes().get(n - 1));
    }

    @Test
    void fullBlock() throws IOException {
        MockConnection c = mock();
        Rrh62000Full s = full(c);
        c.setRegister(0x40, 1);
        c.setRegister(0x00, block(5, WORDS));
        Rrh62000Minimal.Data d = s.readAll();
        assertEquals(new Rrh62000Minimal.Status(5, true, false, true, false), d.status());
        assertEquals(123.4, d.particles().nc0_3(), 1e-9);
        assertEquals(2.0, d.particles().nc4(), 1e-9);
        assertEquals(6.1, d.particles().pm1SmokeUgM3(), 1e-9);
        assertEquals(23.0, d.particles().pm10SmokeUgM3(), 1e-9);
        int[] neg = new int[17];
        neg[11] = 0xFE00;
        c.setRegister(0x00, block(2, neg));
        int before = c.writes().size();
        Rrh62000Minimal.Status st = s.readStatus();
        assertTrue(st.dustAccumulation() && !st.highConcentration() && st.raw() == 2);
        assertEquals(before + 1, c.writes().size());
        assertEquals(-5.12, s.readAll().temperatureC(), 1e-9);
        c.setRegister(0x00, block(0, WORDS));
        Rrh62000Minimal.Particles p = s.readParticles();
        assertEquals(14.0, p.pm2_5SmokeUgM3(), 1e-9);
        assertEquals(50.0, p.nc1(), 1e-9);
    }

    @Test
    void errors() throws IOException {
        MockConnection c = mock();
        Rrh62000Full s = full(c);
        c.setRegister(0x40, 1);
        int[] bad = block(0, WORDS);
        bad[36] ^= 0xFF;
        c.setRegister(0x00, bad);
        assertThrows(Rrh62000Minimal.Rrh62000Exception.class, s::read);
        int[] ff = new int[37];
        java.util.Arrays.fill(ff, 0xFF);
        c.setRegister(0x00, ff);
        Exception e = assertThrows(Rrh62000Minimal.Rrh62000Exception.class, s::read);
        assertTrue(e.getMessage().contains("start-up"));
        c.setRegister(0x40, 0);
        c.setRegister(0x00, block(0, WORDS));
        s.dataTimeoutMs = 0;
        assertThrows(Rrh62000Minimal.Rrh62000Exception.class, s::read);
    }

    @Test
    void commands() throws IOException {
        MockConnection c = mock();
        Rrh62000Full s = full(c);
        c.setRegister(0x40, 0);
        assertFalse(s.dataReady());
        c.setRegister(0x40, 1);
        assertTrue(s.dataReady());
        s.sleep();
        assertArrayEquals(b(0x50, 0x00), last(c));
        s.wake();
        assertArrayEquals(b(0x50, 0x80), last(c));
        s.startCleaning();
        assertArrayEquals(b(0x51, 0x01), last(c));
        c.setRegister(0x74, 1);
        assertTrue(s.cleaningDone());
        s.softReset();
        assertArrayEquals(b(0x52, 0x81), last(c));
        c.failNextWrites(1, 0);
        assertDoesNotThrow(s::softReset);
    }

    @Test
    void settings() throws IOException {
        MockConnection c = mock();
        Rrh62000Full s = full(c);
        s.setMovingAverage(30);
        assertEquals(30, s.getMovingAverage());
        s.setFanSpeed(70);
        assertEquals(70, s.getFanSpeed());
        s.setCleaningInterval(86400);
        assertEquals(0x0B, c.registers().get(0x5A));
        assertEquals(0x40, c.registers().get(0x5B));
        assertEquals(86400, s.getCleaningInterval());
        s.setCleaningTime(20);
        assertEquals(20, s.getCleaningTime());
        assertThrows(IllegalArgumentException.class, () -> s.setMovingAverage(0));
        assertThrows(IllegalArgumentException.class, () -> s.setMovingAverage(61));
        assertThrows(IllegalArgumentException.class, () -> s.setFanSpeed(59));
        assertThrows(IllegalArgumentException.class, () -> s.setFanSpeed(101));
        assertThrows(IllegalArgumentException.class, () -> s.setCleaningInterval(31));
        assertThrows(IllegalArgumentException.class, () -> s.setCleaningInterval(1814430));
        assertThrows(IllegalArgumentException.class, () -> s.setCleaningTime(61));
        c.setRegister(0x71, 0x00, 0x01, 0xE2, 0x40);
        assertEquals(123456L, s.readMoxResistance());
        c.setRegister(0x72, 1, 2, 3, 4, 5, 6);
        assertArrayEquals(b(1, 2, 3, 4, 5, 6), s.uniqueId());
        c.setRegister(0x73, 3, 1, 4);
        assertArrayEquals(new int[] {3, 1, 4}, s.algorithmVersion());
        c.setRegister(0x75, 2, 7);
        assertArrayEquals(new int[] {2, 7}, s.firmwareVersion());
    }
}
