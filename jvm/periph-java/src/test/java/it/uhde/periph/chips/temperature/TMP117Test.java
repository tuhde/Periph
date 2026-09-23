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

class TMP117Test {

    private static final int REG_TEMP      = 0x00;
    private static final int REG_CONFIG    = 0x01;
    private static final int REG_THIGH     = 0x02;
    private static final int REG_TLOW      = 0x03;
    private static final int REG_EEPROM_UL = 0x04;
    private static final int REG_EEPROM1   = 0x05;
    private static final int REG_EEPROM2   = 0x06;
    private static final int REG_OFFSET    = 0x07;
    private static final int REG_EEPROM3   = 0x08;

    /**
     * Word-addressed variant of {@link MockConnection}: TMP117 registers are 16 bits wide at
     * consecutive pointer values, which would overlap in the shared mock's byte-slot model. Each
     * pointer owns one 16-bit word.
     */
    static class WordMock extends MockConnection {
        final Map<Integer, Integer> words = new ConcurrentHashMap<>(Map.of(
                0x00, 0x8000, 0x01, 0x0220, 0x02, 0x6000, 0x03, 0x8000, 0x0F, 0x1117));
        final List<byte[]> log = new CopyOnWriteArrayList<>();

        @Override
        public void write(byte[] data) throws IOException {
            log.add(data.clone());
            if (data.length == 3) words.put(data[0] & 0xFF, ((data[1] & 0xFF) << 8) | (data[2] & 0xFF));
        }

        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            log.add(data.clone());
            int w = words.getOrDefault(data[0] & 0xFF, 0);
            return new byte[]{(byte) (w >> 8), (byte) w};
        }

        byte[] lastWriteTo(int reg) {
            List<byte[]> out = new ArrayList<>();
            for (byte[] w : log) if (w.length >= 2 && (w[0] & 0xFF) == reg) out.add(w);
            return out.get(out.size() - 1);
        }
    }

    @Test
    void identityCheck() throws Exception {
        WordMock m = new WordMock();
        new TMP117Minimal(m);
        assertEquals(1, m.log.size(), "init only reads DEVICE_ID");
        assertArrayEquals(new byte[]{0x0F}, m.log.get(0));

        WordMock bad = new WordMock();
        bad.words.put(0x0F, 0x0118);
        assertThrows(IOException.class, () -> new TMP117Minimal(bad));
        assertThrows(IOException.class, () -> new TMP117Full(bad));
        WordMock rev = new WordMock();
        rev.words.put(0x0F, 0x2117);
        new TMP117Minimal(rev);
    }

    @Test
    void temperatureDecoding() throws Exception {
        WordMock m = new WordMock();
        TMP117Minimal s = new TMP117Minimal(m);
        m.words.put(REG_TEMP, 0x0C80);
        assertEquals(25.0, s.readTemperature());
        m.words.put(REG_TEMP, 0xFFFF);
        assertEquals(-0.0078125, s.readTemperature());
        m.words.put(REG_TEMP, 0xF380);
        assertEquals(-25.0, s.readTemperature());
        m.words.put(REG_TEMP, 0x8000);
        assertEquals(-256.0, s.readTemperature());
        m.words.put(REG_TEMP, 0x7FFF);
        assertEquals(255.9921875, s.readTemperature());
    }

    @Test
    void limitsAndOffset() throws Exception {
        WordMock m = new WordMock();
        TMP117Full s = new TMP117Full(m);
        s.setHighLimit(30.0);
        assertEquals(0x0F00, m.words.get(REG_THIGH));
        assertEquals(30.0, s.getHighLimit());
        s.setLowLimit(-10.25);
        assertEquals(0xFAE0, m.words.get(REG_TLOW));
        assertEquals(-10.25, s.getLowLimit());
        s.setLowLimit(0.004);
        assertEquals(0x0001, m.words.get(REG_TLOW));
        s.setHighLimit(1000.0);
        assertEquals(0x7FFF, m.words.get(REG_THIGH));
        s.setLowLimit(-1000.0);
        assertEquals(0x8000, m.words.get(REG_TLOW));
        s.setTemperatureOffset(-0.5);
        assertEquals(0xFFC0, m.words.get(REG_OFFSET));
        assertEquals(-0.5, s.getTemperatureOffset());
    }

    @Test
    void conversionConfig() throws Exception {
        WordMock m = new WordMock();
        TMP117Full s = new TMP117Full(m);
        assertEquals(new TMP117Full.Config(TMP117Full.Mode.CONTINUOUS, 8, 1.0), s.getConfig());
        s.configure(TMP117Full.Mode.SHUTDOWN, 64, 16.0);
        assertEquals(0x07E0, m.words.get(REG_CONFIG));
        assertEquals(new TMP117Full.Config(TMP117Full.Mode.SHUTDOWN, 64, 16.0), s.getConfig());
        assertTrue(s.isShutdown());
        s.configure(TMP117Full.Mode.CONTINUOUS, 0, 0.01);
        assertEquals(0x0000, m.words.get(REG_CONFIG));
        assertFalse(s.isShutdown());
        s.configure(TMP117Full.Mode.CONTINUOUS, 8, 0.3);
        assertEquals(0x0120, m.words.get(REG_CONFIG), "nearest step is 250 ms");
        s.configure(TMP117Full.Mode.ONE_SHOT, 32, 2.0);
        assertEquals(0x0E40, m.words.get(REG_CONFIG), "nearest step is 1 s");
        assertEquals(TMP117Full.Mode.ONE_SHOT, s.getConfig().mode());
        m.words.put(REG_CONFIG, 0x0800);
        assertEquals(TMP117Full.Mode.CONTINUOUS, s.getConfig().mode(), "MOD=10 reads as continuous");
        m.words.put(REG_CONFIG, 0xF01C);
        s.configure();
        assertEquals(0x023C, m.words.get(REG_CONFIG), "alert bits preserved, flags never written");
        assertThrows(IllegalArgumentException.class, () -> s.configure(TMP117Full.Mode.CONTINUOUS, 16, 1.0));

        m.words.put(REG_CONFIG, 0xE660);
        s.triggerOneShot();
        assertEquals(0x0E60, m.words.get(REG_CONFIG));
        m.words.put(REG_CONFIG, 0x2220);
        assertTrue(s.isDataReady());
        m.words.put(REG_CONFIG, 0x0220);
        assertFalse(s.isDataReady());

        s.reset();
        assertArrayEquals(new byte[]{REG_CONFIG, 0x00, 0x02}, m.lastWriteTo(REG_CONFIG));
    }

    @Test
    void eeprom() throws Exception {
        WordMock m = new WordMock();
        TMP117Full s = new TMP117Full(m);
        s.unlockEeprom();
        assertEquals(0x8000, m.words.get(REG_EEPROM_UL));
        s.lockEeprom();
        assertEquals(0x0000, m.words.get(REG_EEPROM_UL));
        m.words.put(REG_EEPROM_UL, 0x4000);
        assertTrue(s.isEepromBusy());
        m.words.put(REG_EEPROM_UL, 0x8000);
        assertFalse(s.isEepromBusy());

        m.words.put(REG_EEPROM1, 0x1111);
        m.words.put(REG_EEPROM2, 0x2222);
        m.words.put(REG_EEPROM3, 0x3333);
        assertEquals(0x1111, s.readEepromScratch(1));
        assertEquals(0x2222, s.readEepromScratch(2));
        assertEquals(0x3333, s.readEepromScratch(3));
        assertThrows(IllegalArgumentException.class, () -> s.readEepromScratch(4));
        s.writeEepromScratch(2, 0xBEEF);
        assertEquals(0xBEEF, m.words.get(REG_EEPROM2));
        assertThrows(IllegalArgumentException.class, () -> s.writeEepromScratch(1, 0));
        assertThrows(IllegalArgumentException.class, () -> s.writeEepromScratch(3, 0));
        assertEquals(0x1111, m.words.get(REG_EEPROM1));
        assertEquals(0x3333, m.words.get(REG_EEPROM3));
    }

    @Test
    void alertAndInterrupt() throws Exception {
        WordMock m = new WordMock();
        TMP117Full s = new TMP117Full(m);
        s.configureAlert(TMP117Full.AlertMode.THERM, TMP117Full.AlertPolarity.ACTIVE_HIGH,
                TMP117Full.AlertPinFunction.DATA_READY);
        assertEquals(0x023C, m.words.get(REG_CONFIG));
        s.configureAlert();
        assertEquals(0x0220, m.words.get(REG_CONFIG));

        m.words.put(REG_CONFIG, 0x2220);
        assertEquals(0, s.pollInterrupt());
        m.words.put(REG_CONFIG, 0x8220);
        assertEquals(TMP117Full.SOURCE_HIGH, s.pollInterrupt());
        m.words.put(REG_CONFIG, 0x4220);
        assertEquals(TMP117Full.SOURCE_LOW, s.pollInterrupt());
        m.words.put(REG_CONFIG, 0xC220);
        assertEquals(TMP117Full.SOURCE_HIGH | TMP117Full.SOURCE_LOW, s.pollInterrupt());

        // Polling fallback (no intPin): calls back only when the mask changes.
        m.words.put(REG_CONFIG, 0x0220);
        List<Integer> calls = new CopyOnWriteArrayList<>();
        s.onInterrupt(calls::add, null);
        Thread.sleep(30);
        assertTrue(calls.isEmpty());
        m.words.put(REG_CONFIG, 0x8220);
        Thread.sleep(50);
        s.offInterrupt();
        assertEquals(List.of(TMP117Full.SOURCE_HIGH), calls);
    }
}
