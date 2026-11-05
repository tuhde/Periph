package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ADS1114Spec extends Specification {

    /**
     * Word-addressed variant of {@link MockConnection}: the four ADS111x registers are 16 bits
     * wide at consecutive pointer values, which would overlap in the shared mock's byte-slot
     * model. Each pointer owns one 16-bit word.
     */
    static class WordMock extends MockConnection {
        final Map<Integer, Integer> words = new ConcurrentHashMap<>([(0): 0x0000, (1): 0x8583, (2): 0x8000, (3): 0x7FFF])
        final List<byte[]> log = new CopyOnWriteArrayList<>()
        InputPin pin

        @Override
        void write(byte[] data) throws IOException {
            log.add(data.clone())
            if (data.length == 3) words[data[0] & 0xFF] = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF)
        }

        @Override
        byte[] writeRead(byte[] data, int n) throws IOException {
            log.add(data.clone())
            int w = words.getOrDefault(data[0] & 0xFF, 0)
            return [(byte) (w >> 8), (byte) w] as byte[]
        }

        @Override
        InputPin intPin() {
            return pin
        }

        int lastConfig() {
            def last = log.findAll { byte[] b -> b.length == 3 && b[0] == 1 }
            return last ? (((last.last()[1] & 0xFF) << 8) | (last.last()[2] & 0xFF)) : 0
        }
    }

    static class FakePin implements InputPin {
        volatile boolean high

        FakePin(boolean high) {
            this.high = high
        }

        @Override void onEdge(EdgeHandler handler, EdgeTrigger trigger) { }
        @Override void offEdge(EdgeHandler handler) { }
        @Override boolean isHigh() { return high }
        @Override void close() { }
    }

    def "single input, gain and continuous"() {
        given:
        def m = new WordMock()
        def min = new ADS1114Minimal(m)
        m.words[0] = 0x1000

        expect:
        min.readRaw() == 0x1000
        m.lastConfig() == 0x8583

        when:
        def adc = new ADS1114Full(m)
        adc.setGain(ADS1114Full.PGA_0_256)
        adc.startContinuous()

        then:
        adc.lsb() == 0.256d / 32768
        m.lastConfig() == 0x0A83
    }

    def "comparator thresholds and reset"() {
        given:
        def m = new WordMock()
        def adc = new ADS1114Full(m)

        when:
        adc.setGain(ADS1114Full.PGA_0_256)
        adc.setThresholds(-0.1d, 0.1d)
        adc.configureComparator()

        then:
        m.words[2] == 0xCE00
        m.words[3] == 0x3200
        (m.lastConfig() & 3) == 0

        when:
        adc.reset()

        then:
        m.words[1] == 0x8583
        adc.getGain() == 2
    }
}
