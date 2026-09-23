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

class MCP9808Test {

    private static final int REG_CONFIG = 0x01;
    private static final int REG_TA     = 0x05;

    /**
     * Word-addressed variant of {@link MockConnection}: MCP9808 registers are 16 bits wide at
     * consecutive pointer values (MANUFACTURER_ID at 0x06, DEVICE_ID at 0x07), which would
     * overlap in the shared mock's byte-slot model. Each pointer owns one 16-bit word; 1-byte
     * accesses (RESOLUTION) use the word's low byte.
     */
    static class WordMock extends MockConnection {
        final Map<Integer, Integer> words = new ConcurrentHashMap<>(Map.of(0x06, 0x0054, 0x07, 0x0400, 0x08, 0x0003));
        final List<byte[]> log = new CopyOnWriteArrayList<>();

        @Override
        public void write(byte[] data) throws IOException {
            log.add(data.clone());
            if (data.length == 3) words.put(data[0] & 0xFF, ((data[1] & 0xFF) << 8) | (data[2] & 0xFF));
            else if (data.length == 2) words.put(data[0] & 0xFF, data[1] & 0xFF);
        }

        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            log.add(data.clone());
            int w = words.getOrDefault(data[0] & 0xFF, 0);
            return n == 1 ? new byte[]{(byte) w} : new byte[]{(byte) (w >> 8), (byte) w};
        }

        List<byte[]> writesTo(int reg) {
            List<byte[]> out = new ArrayList<>();
            for (byte[] w : log) if (w.length >= 2 && (w[0] & 0xFF) == reg) out.add(w);
            return out;
        }

        byte[] lastWriteTo(int reg) {
            List<byte[]> w = writesTo(reg);
            return w.get(w.size() - 1);
        }
    }

    @Test
    void identityCheck() throws Exception {
        WordMock m = new WordMock();
        new MCP9808Minimal(m);
        assertTrue(m.log.stream().allMatch(w -> w.length == 1), "init makes no register writes");

        WordMock bad = new WordMock();
        bad.words.put(0x06, 0x1234);
        assertThrows(IOException.class, () -> new MCP9808Minimal(bad));
        WordMock badDev = new WordMock();
        badDev.words.put(0x07, 0x0500);
        assertThrows(IOException.class, () -> new MCP9808Full(badDev));
        WordMock rev = new WordMock();
        rev.words.put(0x07, 0x0401);
        new MCP9808Minimal(rev);
    }

    @Test
    void temperatureDecoding() throws Exception {
        WordMock m = new WordMock();
        MCP9808Minimal s = new MCP9808Minimal(m);
        m.words.put(REG_TA, 0x0194);
        assertEquals(25.25, s.readTemperature());
        m.words.put(REG_TA, 0xE194);
        assertEquals(25.25, s.readTemperature());
        m.words.put(REG_TA, 0x1FF0);
        assertEquals(-1.0, s.readTemperature());
        m.words.put(REG_TA, 0x1E6C);
        assertEquals(-25.25, s.readTemperature());
        m.words.put(REG_TA, 0x0001);
        assertEquals(0.0625, s.readTemperature());
    }

    @Test
    void limits() throws Exception {
        WordMock m = new WordMock();
        MCP9808Full s = new MCP9808Full(m);
        s.setUpperLimit(80.0);
        assertEquals(0x0500, m.words.get(0x02));
        assertEquals(80.0, s.getUpperLimit());
        s.setLowerLimit(-25.0);
        assertEquals(0x1E70, m.words.get(0x03));
        assertEquals(-25.0, s.getLowerLimit());
        s.setCriticalLimit(-5.1);
        assertEquals(-5.0, s.getCriticalLimit());
        s.setCriticalLimit(22.13);
        assertEquals(22.25, s.getCriticalLimit());
        s.setUpperLimit(1000.0);
        assertEquals(255.75, s.getUpperLimit());
        s.setLowerLimit(-1000.0);
        assertEquals(-256.0, s.getLowerLimit());
    }

    @Test
    void resolutionAndHysteresis() throws Exception {
        WordMock m = new WordMock();
        MCP9808Full s = new MCP9808Full(m);
        s.setResolution(0.25);
        assertArrayEquals(new byte[]{0x08, 0x01}, m.lastWriteTo(0x08));
        assertEquals(0.25, s.getResolution());
        int before = m.writesTo(0x08).size();
        assertThrows(IllegalArgumentException.class, () -> s.setResolution(0.3));
        assertEquals(before, m.writesTo(0x08).size());

        m.words.put(REG_CONFIG, 0x0000);
        s.setHysteresis(3.0);
        assertEquals(0x0400, m.words.get(REG_CONFIG));
        assertEquals(3.0, s.getHysteresis());
        assertThrows(IllegalArgumentException.class, () -> s.setHysteresis(2.0));
    }

    @Test
    void shutdownLocksAndAlert() throws Exception {
        WordMock m = new WordMock();
        MCP9808Full s = new MCP9808Full(m);

        m.words.put(REG_CONFIG, 0x0400);
        s.shutdown();
        assertEquals(0x0500, m.words.get(REG_CONFIG));
        assertTrue(s.isShutdown());
        s.wake();
        assertEquals(0x0400, m.words.get(REG_CONFIG));
        assertFalse(s.isShutdown());
        m.words.put(REG_CONFIG, 0x0080);
        int before = m.writesTo(REG_CONFIG).size();
        s.shutdown();
        assertEquals(before, m.writesTo(REG_CONFIG).size(), "shutdown is a no-op while locked");

        m.words.put(REG_CONFIG, 0x0000);
        s.lockCriticalLimit();
        assertEquals(0x0080, m.words.get(REG_CONFIG));
        assertTrue(s.isCriticalLimitLocked());
        assertFalse(s.isWindowLimitsLocked());
        m.words.put(REG_CONFIG, 0x0000);
        s.lockWindowLimits();
        assertEquals(0x0040, m.words.get(REG_CONFIG));
        assertTrue(s.isWindowLimitsLocked());

        m.words.put(REG_CONFIG, 0x0000);
        s.configureAlert(MCP9808Full.AlertMode.CRITICAL_ONLY, MCP9808Full.AlertOutput.INTERRUPT,
                MCP9808Full.AlertPolarity.ACTIVE_HIGH);
        assertEquals(0x0007, m.words.get(REG_CONFIG));
        s.configureAlert();
        assertEquals(0x0000, m.words.get(REG_CONFIG));
        m.words.put(REG_CONFIG, 0x0040);
        assertThrows(IllegalStateException.class, () -> s.configureAlert(MCP9808Full.AlertMode.ALL,
                MCP9808Full.AlertOutput.INTERRUPT, MCP9808Full.AlertPolarity.ACTIVE_LOW));
        assertEquals(0x0040, m.words.get(REG_CONFIG));

        m.words.put(REG_CONFIG, 0x0000);
        s.enableAlert();
        assertEquals(0x0008, m.words.get(REG_CONFIG));
        s.disableAlert();
        assertEquals(0x0000, m.words.get(REG_CONFIG));

        m.words.put(REG_CONFIG, 0x0019);
        assertTrue(s.isAlertAsserted());
        s.clearInterrupt();
        assertArrayEquals(new byte[]{REG_CONFIG, 0x00, 0x29}, m.lastWriteTo(REG_CONFIG));
        m.words.put(REG_CONFIG, 0x0009);
        assertFalse(s.isAlertAsserted());
    }

    @Test
    void pollAndOnInterrupt() throws Exception {
        WordMock m = new WordMock();
        MCP9808Full s = new MCP9808Full(m);
        m.words.put(REG_TA, 0x0194);
        assertEquals(0, s.pollInterrupt());
        m.words.put(REG_TA, 0x2194);
        assertEquals(MCP9808Full.SOURCE_LOWER, s.pollInterrupt());
        m.words.put(REG_TA, 0xC194);
        assertEquals(MCP9808Full.SOURCE_UPPER | MCP9808Full.SOURCE_CRITICAL, s.pollInterrupt());

        // Polling fallback (no intPin): calls back only when the mask changes.
        m.words.put(REG_TA, 0x0194);
        List<Integer> calls = new CopyOnWriteArrayList<>();
        s.onInterrupt(calls::add);
        Thread.sleep(30);
        assertTrue(calls.isEmpty(), "no callback without a change");
        m.words.put(REG_TA, 0x4194);
        long deadline = System.currentTimeMillis() + 1000;
        while (calls.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5);
        s.offInterrupt();
        assertEquals(List.of(MCP9808Full.SOURCE_UPPER), calls);
    }
}
