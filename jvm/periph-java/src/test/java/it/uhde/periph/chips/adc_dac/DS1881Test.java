package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DS1881Test {

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static byte[] last(MockConnection c) {
        List<byte[]> w = c.writes();
        return w.get(w.size() - 1);
    }

    @Test
    void initWritesConfigOnlyWhenDifferent() throws Exception {
        MockConnection c = new MockConnection();
        DS1881Minimal pot = new DS1881Minimal(c);
        assertEquals(0, c.writes().size());
        pot.init();
        assertEquals(1, c.writes().size());
        assertArrayEquals(bytes(0x86), last(c));

        MockConnection c2 = new MockConnection();
        c2.queueRead(bytes(10, 20, 0x86));
        new DS1881Minimal(c2).init();
        assertEquals(0, c2.writes().size());

        MockConnection c3 = new MockConnection();
        c3.queueRead(bytes(0, 0, 0xB6));
        new DS1881Minimal(c3).init();
        assertEquals(0, c3.writes().size());
    }

    @Test
    void attenuationEncodingRoundingAndClamping() throws Exception {
        MockConnection c = new MockConnection();
        DS1881Minimal pot = new DS1881Minimal(c);
        pot.setAttenuation(0, 20.0);
        assertArrayEquals(bytes(20), last(c));
        pot.setAttenuation(1, 20.4);
        assertArrayEquals(bytes(0x40 | 20), last(c));
        pot.setAttenuation(1, 20.6);
        assertArrayEquals(bytes(0x40 | 21), last(c));
        pot.setAttenuation(0, 100.0);
        assertArrayEquals(bytes(62), last(c));
        pot.setAttenuation(0, -5.0);
        assertArrayEquals(bytes(0), last(c));
        assertThrows(IllegalArgumentException.class, () -> pot.setAttenuation(2, 0));
        assertThrows(IllegalArgumentException.class, () -> pot.getPosition(-1));
        pot.mute(1);
        assertArrayEquals(bytes(0x7F), last(c));
        pot.setPosition(0, 99);
        assertArrayEquals(bytes(63), last(c));
        pot.setPosition(0, -3);
        assertArrayEquals(bytes(0), last(c));
    }

    @Test
    void readsMaskUpperBits() throws Exception {
        MockConnection c = new MockConnection();
        for (int i = 0; i < 4; i++) c.queueRead(bytes(0xC5, 0x3F, 0x86));
        DS1881Minimal pot = new DS1881Minimal(c);
        assertEquals(5, pot.getPosition(0));
        assertEquals(63, pot.getPosition(1));
        assertEquals(80.0, pot.getAttenuation(1));
        assertEquals(5.0, pot.getAttenuation(0));
        assertEquals(0, c.writes().size());
    }

    @Test
    void stereoWriteIsOneTransaction() throws Exception {
        MockConnection c = new MockConnection();
        DS1881Full full = new DS1881Full(c);
        full.setBoth(10, 99);
        assertEquals(1, c.writes().size());
        assertArrayEquals(bytes(10, 0x40 | 63), last(c));
        full.setAttenuationBoth(30.0);
        assertArrayEquals(bytes(30, 0x40 | 30), last(c));
        full.setAttenuationBoth(5.0, 70.0);
        assertArrayEquals(bytes(5, 0x7F), last(c));
    }

    @Test
    void option1And2Tables() throws Exception {
        MockConnection c = new MockConnection();
        DS1881Full full = new DS1881Full(c);
        assertEquals(0.0, full.positionToDb(0));
        assertEquals(62.0, full.positionToDb(62));
        assertEquals(80.0, full.positionToDb(63));
        assertEquals(62, full.maxPosition());
        assertEquals(12, full.dbToPosition(12.4));
        assertEquals(63, full.dbToPosition(75.0));

        full.configure(2, true, false);
        assertArrayEquals(bytes(0x87), last(c));
        int n = c.writes().size();
        full.configure(2, true, false);
        assertEquals(n, c.writes().size());
        double[] want = {0, 12, 14, 36, 39, 60, 80};
        int[] pos = {0, 12, 13, 24, 25, 32, 33};
        for (int i = 0; i < pos.length; i++) assertEquals(want[i], full.positionToDb(pos[i]));
        assertEquals(32, full.maxPosition());
        assertEquals(13, full.dbToPosition(14.0));
        assertEquals(24, full.dbToPosition(36.0));
        assertEquals(32, full.dbToPosition(60.0));
        assertEquals(24, full.dbToPosition(37.0));
        assertEquals(25, full.dbToPosition(38.0));
        assertEquals(33, full.dbToPosition(70.0));
        full.setBoth(40, 40);
        assertArrayEquals(bytes(33, 0x40 | 33), last(c));
        full.mute(0);
        assertArrayEquals(bytes(33), last(c));
        assertThrows(IllegalArgumentException.class, () -> full.configure(3, true, false));
    }

    @Test
    void configBitsAndNonvolatilePolling() throws Exception {
        MockConnection c = new MockConnection();
        DS1881Full full = new DS1881Full(c);
        full.setZeroCrossing(false);
        assertArrayEquals(bytes(0x84), last(c));
        full.setNonvolatile(true);
        assertArrayEquals(bytes(0x80), last(c));
        full.setOption(2);
        assertArrayEquals(bytes(0x81), last(c));
        c.failNextReads(1);
        full.setBoth(1, 2);
        assertArrayEquals(bytes(1, 0x42), last(c));
    }

    @Test
    void getConfigAndReadAll() throws Exception {
        MockConnection c = new MockConnection();
        c.queueRead(bytes(0, 0, 0x87));
        c.queueRead(bytes(1, 2, 0x82));
        c.queueRead(bytes(0x41, 0x02, 0x86));
        DS1881Full full = new DS1881Full(c);
        DS1881Full.Config cfg = full.getConfig();
        assertEquals(2, cfg.option);
        assertTrue(cfg.zeroCrossing);
        assertFalse(cfg.nonvolatile);
        assertEquals(0x87, cfg.raw);
        assertEquals(32, full.maxPosition());
        cfg = full.getConfig();
        assertTrue(cfg.nonvolatile);
        assertEquals(1, cfg.option);
        assertArrayEquals(new int[] {1, 2, 0x86}, full.readAll());
    }

    @Test
    void waitReadyRecoversAndTimesOut() throws Exception {
        MockConnection c = new MockConnection();
        DS1881Full full = new DS1881Full(c);
        c.failNextReads(3);
        full.waitReady(0.05);
        c.failNextReads(1000);
        assertThrows(IOException.class, () -> full.waitReady(0.01));
    }

    @Test
    void storeAndReset() throws Exception {
        MockConnection c = new MockConnection();
        c.queueRead(bytes(12, 34, 0x86));
        DS1881Full full = new DS1881Full(c);
        full.store();
        List<byte[]> w = c.writes();
        assertEquals(3, w.size());
        assertArrayEquals(bytes(0x82), w.get(0));
        assertArrayEquals(bytes(12, 0x40 | 34), w.get(1));
        assertArrayEquals(bytes(0x86), w.get(2));

        MockConnection c2 = new MockConnection();
        DS1881Full f2 = new DS1881Full(c2);
        f2.configure(2, false, true);
        f2.reset();
        assertArrayEquals(bytes(0x86, 0x3F, 0x7F), last(c2));
        assertEquals(62, f2.maxPosition());
    }
}
