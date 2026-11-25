package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ADS1014Test {

    /**
     * Word-addressed variant of {@link MockConnection}: the ADS1014 registers are 16 bits wide at
     * consecutive pointer values, which would overlap in the shared mock's byte-slot model. Each
     * pointer owns one 16-bit word.
     */
    static class WordMock extends MockConnection {
        final Map<Integer, Integer> words = new ConcurrentHashMap<>(Map.of(0, 0x0000, 1, 0x8583, 2, 0x8000, 3, 0x7FFF));
        final List<byte[]> log = new CopyOnWriteArrayList<>();
        InputPin pin;

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

        @Override
        public InputPin intPin() {
            return pin;
        }

        int lastConfigWrite() {
            int last = -1;
            for (byte[] w : log) if (w.length == 3 && w[0] == 1) last = ((w[1] & 0xFF) << 8) | (w[2] & 0xFF);
            return last;
        }
    }

    static class FakePin implements InputPin {
        boolean high = true;
        EdgeHandler handler;
        EdgeTrigger trigger;

        @Override public void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t; }
        @Override public void offEdge(EdgeHandler h) { if (handler == h) handler = null; }
        @Override public boolean isHigh() { return high; }
        @Override public void close() { }
    }

    @Test
    void minimalSingleShot() throws IOException {
        WordMock mock = new WordMock();
        ADS1014Minimal adc = new ADS1014Minimal(mock);
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
    void gainAndDataRate() throws IOException {
        WordMock mock = new WordMock();
        ADS1014Full adc = new ADS1014Full(mock);
        adc.setGain(ADS1014Full.PGA_6_144);
        assertEquals(0, adc.getGain());
        assertEquals(6.144, adc.fullScale(), 1e-12);
        assertEquals(0.003, adc.lsb(), 1e-12);
        adc.setGain(ADS1014Full.PGA_0_256);
        assertEquals(0.000125, adc.lsb(), 1e-12);
        mock.words.put(0, 0x4000);
        assertEquals(0.256 * 0x400 / 2048.0, adc.readVoltage(), 1e-9);
        assertEquals(0x8000 | (5 << 9) | 0x0100 | (4 << 5) | 0x3, mock.lastConfigWrite());
        for (int bad : new int[]{-1, 6, 7}) assertThrows(IllegalArgumentException.class, () -> adc.setGain(bad));

        adc.setDataRate(ADS1014Full.DR_128);
        assertEquals(128, adc.dataRateSps());
        adc.setDataRate(ADS1014Full.DR_3300);
        assertEquals(3300, adc.dataRateSps());
        assertEquals(6, adc.getDataRate());
        assertThrows(IllegalArgumentException.class, () -> adc.setDataRate(7));
    }

    @Test
    void continuousMode() throws IOException {
        WordMock mock = new WordMock();
        ADS1014Full adc = new ADS1014Full(mock);
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
    }

    @Test
    void comparatorAndThresholds() throws IOException {
        WordMock mock = new WordMock();
        ADS1014Full adc = new ADS1014Full(mock);
        adc.configureComparator(ADS1014Full.COMP_WINDOW, ADS1014Full.ACTIVE_HIGH, true, ADS1014Full.QUEUE_4);
        assertEquals(0x0580 | 0x10 | 0x08 | 0x04 | 0x02, mock.lastConfigWrite());
        adc.disableComparator();
        assertEquals(3, mock.lastConfigWrite() & 0x3);
        assertThrows(IllegalArgumentException.class, () -> adc.configureComparator(0, 0, false, 3));

        adc.setThresholds(-0.5, 1.0);
        assertEquals((-500 & 0xFFF) << 4, (int) mock.words.get(2));
        assertEquals(1000 << 4, (int) mock.words.get(3));
        assertArrayEquals(new int[]{-500, 1000}, adc.getThresholdsRaw());
        adc.setThresholds(-10, 10);
        assertArrayEquals(new int[]{-2048, 2047}, adc.getThresholdsRaw());
        assertThrows(IllegalArgumentException.class, () -> adc.setThresholds(1.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> adc.setHighThresholdRaw(2048));

        mock.log.clear();
        adc.clearAlert();
        assertEquals(1, mock.log.size());
        assertArrayEquals(new byte[]{0}, mock.log.get(0));

        adc.reset();
        assertEquals(0x8583, (int) mock.words.get(1));
        assertEquals(0x8000, (int) mock.words.get(2));
        assertEquals(0x7FFF, (int) mock.words.get(3));
    }

    @Test
    void interruptRouting() throws IOException {
        WordMock mock = new WordMock();
        FakePin pin = new FakePin();
        mock.pin = pin;
        ADS1014Full adc = new ADS1014Full(mock);

        adc.enableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY);
        assertEquals(0x8000, (int) mock.words.get(3));
        assertEquals(0x0000, (int) mock.words.get(2));
        assertEquals(0, mock.words.get(1) & 0x3);
        assertEquals(0, adc.pollInterrupt(), "pin high = idle for an active-low alert");
        pin.high = false;
        assertEquals(ADS1014Full.SOURCE_CONVERSION_READY, adc.pollInterrupt());

        adc.setHighThresholdRaw(-5);
        adc.setLowThresholdRaw(10);
        assertThrows(IllegalArgumentException.class, () -> adc.enableInterrupt(ADS1014Full.SOURCE_COMPARATOR));
        assertThrows(IllegalArgumentException.class, () -> adc.enableInterrupt(0x04));
        adc.setThresholds(-0.1, 0.1);
        adc.configureComparator(ADS1014Full.COMP_TRADITIONAL, ADS1014Full.ACTIVE_LOW, true, ADS1014Full.QUEUE_1);
        adc.enableInterrupt(ADS1014Full.SOURCE_COMPARATOR);
        assertArrayEquals(new int[]{-100, 100}, adc.getThresholdsRaw());
        mock.log.clear();
        assertEquals(ADS1014Full.SOURCE_COMPARATOR, adc.pollInterrupt());
        assertTrue(mock.log.stream().anyMatch(w -> w.length == 1 && w[0] == 0), "latched alert cleared by reading CONVERSION");

        List<Integer> got = new ArrayList<>();
        adc.onInterrupt(got::add);
        assertEquals(EdgeTrigger.FALLING, pin.trigger);
        pin.handler.onEdge();
        assertEquals(List.of(ADS1014Full.SOURCE_COMPARATOR), got);
        adc.offInterrupt();
        assertNull(pin.handler);

        adc.disableInterrupt(ADS1014Full.SOURCE_COMPARATOR);
        assertEquals(3, mock.words.get(1) & 0x3);
        assertEquals(0, adc.pollInterrupt());
    }

    @Test
    void pollFallbackWithoutPin() throws IOException {
        WordMock mock = new WordMock();
        ADS1014Full adc = new ADS1014Full(mock);
        adc.enableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY);
        mock.words.put(1, mock.words.get(1) | 0x8000);  // the real chip reads OS = 1 when idle
        assertEquals(ADS1014Full.SOURCE_CONVERSION_READY, adc.pollInterrupt());
        mock.words.put(1, mock.words.get(1) & 0x7FFF);
        assertEquals(0, adc.pollInterrupt());
    }
}
