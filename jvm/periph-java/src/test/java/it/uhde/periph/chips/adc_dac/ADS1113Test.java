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

class ADS1113Test {

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
    void fixedRangeContinuousAndReset() throws IOException {
        WordMock m = new WordMock();
        ADS1113Minimal min = new ADS1113Minimal(m);
        m.words.put(0, 0x0200);
        assertEquals(0x0200 * 0.0000625, min.readVoltage());
        ADS1113Full adc = new ADS1113Full(m);
        assertEquals(2.048, adc.fullScale());
        assertEquals(2.048 / 32768, adc.lsb());
        adc.setDataRate(ADS1113Full.DR_8);
        adc.startContinuous();
        assertEquals(0x0403, m.lastConfig());
        adc.reset();
        assertEquals(0x8583, m.words.get(1));
        assertEquals(128, adc.dataRateSps());
    }
}
