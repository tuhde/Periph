package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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

class ADS1115Test {

    /**
     * Word-addressed variant of {@link MockConnection}: the four ADS111x registers are 16 bits
     * wide at consecutive pointer values, which would overlap in the shared mock's byte-slot
     * model. Each pointer owns one 16-bit word.
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

        int lastConfig() {
            int last = 0;
            for (byte[] w : log) if (w.length == 3 && w[0] == 1) last = ((w[1] & 0xFF) << 8) | (w[2] & 0xFF);
            return last;
        }
    }

    static class FakePin implements InputPin {
        volatile boolean high;

        FakePin(boolean high) {
            this.high = high;
        }

        @Override public void onEdge(EdgeHandler handler, EdgeTrigger trigger) { }
        @Override public void offEdge(EdgeHandler handler) { }
        @Override public boolean isHigh() { return high; }
        @Override public void close() { }
    }

    @Test
    void minimalSingleEnded() throws IOException {
        WordMock m = new WordMock();
        ADS1115Minimal adc = new ADS1115Minimal(m);
        assertTrue(m.log.isEmpty(), "init makes no bus traffic");
        m.words.put(0, 0x4000);
        assertEquals(0x4000, adc.readRaw());
        assertEquals(0xC583, m.lastConfig());
        m.words.put(0, 0xFFFF);
        assertEquals(-1, adc.readRaw(2));
        assertEquals(0xE583, m.lastConfig());
        adc.readRaw(9);
        assertEquals(0xF583, m.lastConfig(), "channel clamps to 3");
        m.words.put(0, 0x4000);
        assertEquals(0x4000 * 0.0000625, adc.readVoltage());
    }

    @Test
    void fullGainRateDifferentialContinuous() throws IOException {
        WordMock m = new WordMock();
        ADS1115Full adc = new ADS1115Full(m);
        adc.setGain(ADS1115Full.PGA_4_096);
        assertEquals(4.096, adc.fullScale());
        assertEquals(4.096 / 32768, adc.lsb());
        m.words.put(0, 0x2000);
        assertEquals(0x2000 * (4.096 / 32768), adc.readVoltage(1));
        assertEquals(0xD383, m.lastConfig());
        assertThrows(IllegalArgumentException.class, () -> adc.setGain(6));
        adc.setDataRate(ADS1115Full.DR_860);
        assertEquals(7, adc.getDataRate());
        assertEquals(860, adc.dataRateSps());
        adc.readRaw();
        assertEquals(0xC3E3, m.lastConfig());
        assertEquals(0x2000, adc.readDifferentialRaw(ADS1115Full.DIFF_2_3));
        assertEquals(0xB3E3, m.lastConfig());
        assertThrows(IllegalArgumentException.class, () -> adc.readDifferentialRaw(4));
        adc.startContinuous(ADS1115Full.MUX_AIN1_GND);
        assertEquals(0x52E3, m.lastConfig());
        m.words.put(0, 0x0100);
        assertEquals(256, adc.readContinuousRaw());
        adc.stopContinuous();
        assertEquals(0x53E3, m.lastConfig());
        m.words.put(1, 0x0583);
        assertTrue(adc.isConverting());
        m.words.put(1, 0x8583);
        assertFalse(adc.isConverting());
        assertEquals(0x8583, adc.readConfig());
    }

    @Test
    void thresholdsAndComparator() throws IOException {
        WordMock m = new WordMock();
        ADS1115Full adc = new ADS1115Full(m);
        adc.setThresholds(0.5, 3.0);
        assertEquals(8000, m.words.get(2));
        assertEquals(0x7FFF, m.words.get(3), "3.0 V clamps to the code range");
        adc.setHighThresholdRaw(-100);
        assertEquals(0xFF9C, m.words.get(3));
        adc.setLowThresholdRaw(-200);
        assertArrayEquals(new int[]{-200, -100}, adc.getThresholdsRaw());
        assertThrows(IllegalArgumentException.class, () -> adc.setThresholds(1.0, 1.0));
        adc.setThresholds(0.5, 1.5);
        adc.configureComparator(ADS1115Full.COMP_WINDOW, ADS1115Full.ACTIVE_HIGH, true, ADS1115Full.QUEUE_2);
        assertEquals(0x0400 | 0x0100 | 0x0080 | 0x0010 | 0x0008 | 0x0004 | 0x0001, m.lastConfig());
        adc.disableComparator();
        assertEquals(3, m.lastConfig() & 3);
        m.log.clear();
        adc.clearAlert();
        assertEquals(1, m.log.size());
        assertEquals(0, m.log.get(0)[0]);
    }

    @Test
    void interruptRoutingAndPolling() throws IOException {
        WordMock m = new WordMock();
        ADS1115Full adc = new ADS1115Full(m);
        adc.enableInterrupt(ADS1115Full.SOURCE_CONVERSION_READY);
        assertEquals(0x8000, m.words.get(3));
        assertEquals(0x0000, m.words.get(2));
        assertEquals(0, m.lastConfig() & 3);
        m.words.put(1, 0x8583);
        assertEquals(ADS1115Full.SOURCE_CONVERSION_READY, adc.pollInterrupt());
        m.words.put(1, 0x0583);
        assertEquals(0, adc.pollInterrupt());
        FakePin pin = new FakePin(false);
        m.pin = pin;
        assertEquals(ADS1115Full.SOURCE_CONVERSION_READY, adc.pollInterrupt());
        pin.high = true;
        assertEquals(0, adc.pollInterrupt());
        adc.disableInterrupt(ADS1115Full.SOURCE_CONVERSION_READY);
        assertEquals(3, m.lastConfig() & 3);
        assertEquals(0, adc.pollInterrupt());
        adc.setThresholds(0.5, 1.5);
        adc.enableInterrupt(ADS1115Full.SOURCE_COMPARATOR);
        assertEquals(0, m.lastConfig() & 3);
        pin.high = false;
        assertEquals(ADS1115Full.SOURCE_COMPARATOR, adc.pollInterrupt());
        assertThrows(IllegalArgumentException.class, () -> adc.enableInterrupt(0x80));
        adc.setGain(0);
        adc.reset();
        assertEquals(0x8583, m.words.get(1));
        assertEquals(0x8000, m.words.get(2));
        assertEquals(0x7FFF, m.words.get(3));
        assertEquals(2, adc.getGain());
        assertEquals(4, adc.getDataRate());
    }
}
