package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ADS1113Spec extends Specification {

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

    def "fixed range, continuous and reset"() {
        given:
        def m = new WordMock()
        def min = new ADS1113Minimal(m)
        m.words[0] = 0x0200

        expect:
        min.readVoltage() == 0x0200 * 0.0000625d

        when:
        def adc = new ADS1113Full(m)
        adc.setDataRate(ADS1113Full.DR_8)
        adc.startContinuous()

        then:
        adc.fullScale() == 2.048d
        adc.lsb() == 2.048d / 32768
        m.lastConfig() == 0x0403

        when:
        adc.reset()

        then:
        m.words[1] == 0x8583
        adc.dataRateSps() == 128
    }
}
