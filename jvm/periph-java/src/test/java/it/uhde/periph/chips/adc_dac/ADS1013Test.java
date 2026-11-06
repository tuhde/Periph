package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ADS1013Test {

    /** Word-addressed variant of {@link MockConnection}: one 16-bit word per register pointer. */
    static class WordMock extends MockConnection {
        final Map<Integer, Integer> words = new ConcurrentHashMap<>(Map.of(0, 0x0000, 1, 0x8583));
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

        int lastConfigWrite() {
            int last = -1;
            for (byte[] w : log) if (w.length == 3 && w[0] == 1) last = ((w[1] & 0xFF) << 8) | (w[2] & 0xFF);
            return last;
        }
    }

    @Test
    void minimalSingleShot() throws IOException {
        WordMock mock = new WordMock();
        ADS1013Minimal adc = new ADS1013Minimal(mock);
        assertTrue(mock.log.isEmpty(), "init makes no bus traffic");
        mock.words.put(0, 0x7FF0);
        assertEquals(2047, adc.readRaw());
        assertEquals(0x8583, mock.lastConfigWrite());
        mock.words.put(0, 0x8000);
        assertEquals(-2048, adc.readRaw());
        mock.words.put(0, 0xFFF0);
        assertEquals(-1, adc.readRaw());
        mock.words.put(0, 0x3E80);
        assertEquals(1.0, adc.readVoltage(), 1e-9);
    }

    @Test
    void dataRateAndFixedRange() throws IOException {
        WordMock mock = new WordMock();
        ADS1013Full adc = new ADS1013Full(mock);
        assertEquals(2.048, adc.fullScale(), 0.0);
        assertEquals(0.001, adc.lsb(), 0.0);
        adc.setDataRate(ADS1013Full.DR_128);
        assertEquals(128, adc.dataRateSps());
        assertEquals(0, adc.getDataRate());
        adc.setDataRate(ADS1013Full.DR_3300);
        assertEquals(3300, adc.dataRateSps());
        assertEquals(6, adc.getDataRate());
        mock.words.put(0, 0x1000);
        adc.readRaw();
        assertEquals(0x8000 | 0x0403 | 0x0100 | (6 << 5), mock.lastConfigWrite());
        for (int bad : new int[]{-1, 7}) assertThrows(IllegalArgumentException.class, () -> adc.setDataRate(bad));
    }

    @Test
    void continuousModeAndReset() throws IOException {
        WordMock mock = new WordMock();
        ADS1013Full adc = new ADS1013Full(mock);
        adc.startContinuous();
        assertEquals(0x0483, mock.lastConfigWrite());
        mock.words.put(0, 0x1000);
        mock.log.clear();
        assertEquals(256, adc.readContinuousRaw());
        assertEquals(-1, mock.lastConfigWrite(), "reading must not write Config");
        assertEquals(0.256, adc.readContinuous(), 1e-9);
        adc.stopContinuous();
        assertEquals(0x0583, mock.lastConfigWrite());
        mock.words.put(1, 0x0583);
        assertTrue(adc.isConverting());
        mock.words.put(1, 0x8583);
        assertFalse(adc.isConverting());
        assertEquals(0x8583, adc.readConfig());
        adc.setDataRate(ADS1013Full.DR_128);
        adc.reset();
        assertEquals(0x8583, (int) mock.words.get(1));
        assertEquals(ADS1013Full.DR_1600, adc.getDataRate());
    }
}
