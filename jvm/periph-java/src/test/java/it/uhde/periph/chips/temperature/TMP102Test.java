package it.uhde.periph.chips.temperature;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TMP102Test {

    private static final int REG_TEMP   = 0x00;
    private static final int REG_CONFIG = 0x01;
    private static final int REG_T_HIGH = 0x03;

    /**
     * Word-addressed variant of {@link MockConnection}: TMP102 registers are 16 bits wide at
     * consecutive pointer values, which would overlap in the shared mock's byte-slot model. Each
     * pointer owns one 16-bit word. {@code osDone} controls whether CONFIGURATION reads OS = 1.
     */
    static class WordMock extends MockConnection {
        final Map<Integer, Integer> words = new ConcurrentHashMap<>(
                Map.of(0x00, 0x0000, 0x01, 0x60A0, 0x02, 0x4B00, 0x03, 0x5000));
        final List<byte[]> log = new CopyOnWriteArrayList<>();
        volatile boolean osDone = true;

        @Override
        public void write(byte[] data) throws IOException {
            log.add(data.clone());
            if (data.length == 3) words.put(data[0] & 0xFF, ((data[1] & 0xFF) << 8) | (data[2] & 0xFF));
        }

        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            log.add(data.clone());
            int w = words.getOrDefault(data[0] & 0xFF, 0);
            if ((data[0] & 0xFF) == 0x01) w = osDone ? (w | 0x8000) : (w & 0x7FFF);
            return new byte[]{(byte) (w >> 8), (byte) w};
        }

        List<byte[]> writesTo(int reg) {
            List<byte[]> out = new ArrayList<>();
            for (byte[] w : log) if (w.length == 3 && (w[0] & 0xFF) == reg) out.add(w);
            return out;
        }
    }

    @Test
    void constructorMakesNoBusAccess() {
        WordMock m = new WordMock();
        new TMP102Minimal(m);
        new TMP102Full(m);
        assertTrue(m.log.isEmpty());
    }

    @Test
    void temperatureDecoding() throws IOException {
        WordMock m = new WordMock();
        TMP102Minimal s = new TMP102Minimal(m);
        int[][] cases = {{0x1900, 25}, {0xE700, -25}, {0xC900, -55}, {(2400 << 3) | 1, 150},
                {((-1600 << 3) & 0xFFFF) | 1, -100}};
        for (int[] c : cases) {
            m.words.put(REG_TEMP, c[0]);
            assertEquals((double) c[1], s.readTemperature(), "raw " + c[0]);
        }
        m.words.put(REG_TEMP, 0x7FF0);
        assertEquals(127.9375, s.readTemperature());
    }

    @Test
    void configReadModifyWrite() throws IOException {
        WordMock m = new WordMock();
        TMP102Full s = new TMP102Full(m);
        assertEquals(0x60A0 | 0x8000, s.readConfig());
        s.setConversionRate(8);
        assertEquals(0x00C0, m.words.get(REG_CONFIG) & 0x00C0);
        assertEquals(0x6000, m.words.get(REG_CONFIG) & 0x6000);
        s.setConversionRate(0.25);
        assertEquals(0, m.words.get(REG_CONFIG) & 0x00C0);
        assertThrows(IllegalArgumentException.class, () -> s.setConversionRate(2));
        s.setFaultQueue(6);
        assertEquals(0x1800, m.words.get(REG_CONFIG) & 0x1800);
        s.setFaultQueue(2);
        assertEquals(0x0800, m.words.get(REG_CONFIG) & 0x1800);
        assertThrows(IllegalArgumentException.class, () -> s.setFaultQueue(3));
        s.setPolarity(true);
        assertEquals(0x0400, m.words.get(REG_CONFIG) & 0x0400);
        s.setPolarity(false);
        assertEquals(0, m.words.get(REG_CONFIG) & 0x0400);
        s.setThermostatMode(true);
        assertEquals(0x0200, m.words.get(REG_CONFIG) & 0x0200);
        s.setThermostatMode(false);
        assertEquals(0, m.words.get(REG_CONFIG) & 0x0200);
        s.setExtendedMode(true);
        assertEquals(0x0010, m.words.get(REG_CONFIG) & 0x0010);
        s.setExtendedMode(false);
        assertEquals(0, m.words.get(REG_CONFIG) & 0x0010);
        s.shutdown();
        assertEquals(0x0100, m.words.get(REG_CONFIG) & 0x0100);
        s.wake();
        assertEquals(0, m.words.get(REG_CONFIG) & 0x0100);
        s.writeConfig(0xFFFF);
        assertEquals(0x6000, m.words.get(REG_CONFIG) & 0x602F);
        m.words.put(REG_TEMP, 0x1900);
        assertEquals(400, s.readRaw());
    }

    @Test
    void limits() throws IOException {
        WordMock m = new WordMock();
        TMP102Full s = new TMP102Full(m);
        s.setTHigh(30.0);
        assertEquals(0x1E00, m.words.get(REG_T_HIGH));
        assertEquals(30.0, s.getTHigh());
        s.setTLow(-10.5);
        assertEquals(-10.5, s.getTLow());
        s.setTHigh(500.0);
        assertEquals(127.9375, s.getTHigh());
        s.setExtendedMode(true);
        s.setTHigh(140.0);
        assertEquals(2240 << 3, m.words.get(REG_T_HIGH));
        assertEquals(140.0, s.getTHigh());
        s.setTHigh(1000.0);
        assertEquals(150.0, s.getTHigh());
    }

    @Test
    void oneShot() throws IOException {
        WordMock m = new WordMock();
        TMP102Full s = new TMP102Full(m);
        m.words.put(REG_TEMP, 0x1900);
        assertEquals(25.0, s.oneShot());
        boolean sawOs = false;
        for (byte[] w : m.writesTo(REG_CONFIG)) if ((w[1] & 0x80) != 0) sawOs = true;
        assertTrue(sawOs);
        assertEquals(0, m.words.get(REG_CONFIG) & 0x0100);
        m.words.put(REG_CONFIG, 0x6180);
        s.oneShot();
        assertEquals(0x0100, m.words.get(REG_CONFIG) & 0x0100);
        m.osDone = false;
        assertThrows(IOException.class, s::oneShot);
    }

    @Test
    void alertStateAndPoll() throws IOException {
        WordMock m = new WordMock();
        TMP102Full s = new TMP102Full(m);
        m.words.put(REG_CONFIG, 0x6020);
        assertFalse(s.alertState());
        m.words.put(REG_CONFIG, 0x6000);
        assertTrue(s.alertState());
        m.words.put(REG_CONFIG, 0x6420);
        assertTrue(s.alertState());
        m.words.put(REG_CONFIG, 0x6400);
        assertFalse(s.alertState());
        m.log.clear();
        m.words.put(REG_CONFIG, 0x6000);
        assertTrue(s.pollInterrupt());
        assertArrayEquals(new byte[]{REG_TEMP}, m.log.get(m.log.size() - 1));
    }

    @Test
    void generalCallReset() throws IOException {
        WordMock m = new WordMock();
        TMP102Full s = new TMP102Full(m);
        s.reset();
        assertEquals(1, m.log.size());
        assertArrayEquals(new byte[]{0x00, 0x06}, m.log.get(0));
    }

    @Test
    void pollingFallbackReportsTemperature() throws Exception {
        WordMock m = new WordMock();
        TMP102Full s = new TMP102Full(m);
        m.words.put(REG_TEMP, 0x1900);
        m.words.put(REG_CONFIG, 0x6000);
        List<Double> calls = new CopyOnWriteArrayList<>();
        s.onInterrupt(calls::add, null);
        Thread.sleep(300);
        s.offInterrupt();
        assertTrue(!calls.isEmpty() && calls.get(0) == 25.0);
    }
}
