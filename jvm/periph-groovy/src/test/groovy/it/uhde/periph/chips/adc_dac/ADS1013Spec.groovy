package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ADS1013Spec extends Specification {

    /** Word-addressed variant of {@link MockConnection}: one 16-bit word per register pointer. */
    static class WordMock extends MockConnection {
        final Map<Integer, Integer> words = new ConcurrentHashMap<>([(0): 0x0000, (1): 0x8583])
        final List<byte[]> log = new CopyOnWriteArrayList<>()

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

        int lastConfigWrite() {
            int last = -1
            for (byte[] w : log) if (w.length == 3 && w[0] == 1) last = ((w[1] & 0xFF) << 8) | (w[2] & 0xFF)
            return last
        }
    }

    def "minimal single-shot read"() {
        given:
        def m = new WordMock()
        def adc = new ADS1013Minimal(m)

        expect: 'init makes no bus traffic'
        m.log.isEmpty()

        when:
        m.words[0] = raw

        then:
        adc.readRaw() == expected
        m.lastConfigWrite() == 0x8583

        where:
        raw    || expected
        0x7FF0 || 2047
        0x8000 || -2048
        0xFFF0 || -1
    }

    def "voltage conversion"() {
        given:
        def m = new WordMock()
        def adc = new ADS1013Minimal(m)

        when:
        m.words[0] = 0x3E80

        then:
        Math.abs(adc.readVoltage() - 1.0d) < 1e-9
    }

    def "data rate and fixed range"() {
        given:
        def m = new WordMock()
        def adc = new ADS1013Full(m)

        expect:
        adc.fullScale() == 2.048d
        adc.lsb() == 0.001d

        when:
        adc.setDataRate(ADS1013Full.DR_128)

        then:
        adc.dataRateSps() == 128
        adc.dataRate == 0

        when:
        adc.setDataRate(ADS1013Full.DR_3300)
        m.words[0] = 0x1000
        adc.readRaw()

        then:
        adc.dataRateSps() == 3300
        adc.dataRate == 6
        m.lastConfigWrite() == (0x8000 | 0x0403 | 0x0100 | (6 << 5))

        when:
        adc.setDataRate(7)

        then:
        thrown(IllegalArgumentException)
    }

    def "continuous mode and reset"() {
        given:
        def m = new WordMock()
        def adc = new ADS1013Full(m)

        when:
        adc.startContinuous()
        m.words[0] = 0x1000
        m.log.clear()

        then:
        adc.readContinuousRaw() == 256
        m.lastConfigWrite() == -1
        Math.abs(adc.readContinuous() - 0.256d) < 1e-9

        when:
        adc.stopContinuous()

        then:
        m.lastConfigWrite() == 0x0583

        when:
        m.words[1] = 0x0583

        then:
        adc.isConverting()

        when:
        m.words[1] = 0x8583

        then:
        !adc.isConverting()
        adc.readConfig() == 0x8583

        when:
        adc.setDataRate(ADS1013Full.DR_128)
        adc.reset()

        then:
        m.words[1] == 0x8583
        adc.dataRate == ADS1013Full.DR_1600
    }
}
