package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ADS1014Spec extends Specification {

    /**
     * Word-addressed variant of {@link MockConnection}: the ADS1014 registers are 16 bits wide at
     * consecutive pointer values, which would overlap in the shared mock's byte-slot model. Each
     * pointer owns one 16-bit word.
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

        int lastConfigWrite() {
            int last = -1
            for (byte[] w : log) if (w.length == 3 && w[0] == 1) last = ((w[1] & 0xFF) << 8) | (w[2] & 0xFF)
            return last
        }
    }

    static class FakePin implements InputPin {
        boolean high = true
        EdgeHandler handler
        EdgeTrigger trigger

        @Override void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t }
        @Override void offEdge(EdgeHandler h) { if (handler == h) handler = null }
        @Override boolean isHigh() { return high }
        @Override void close() { }
    }

    def "minimal single-shot read"() {
        given:
        def m = new WordMock()
        def adc = new ADS1014Minimal(m)

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

    def "voltage conversion at the default range"() {
        given:
        def m = new WordMock()
        def adc = new ADS1014Minimal(m)

        when:
        m.words[0] = 0x3E80

        then:
        Math.abs(adc.readVoltage() - 1.0d) < 1e-9
    }

    def "gain and data rate"() {
        given:
        def m = new WordMock()
        def adc = new ADS1014Full(m)

        when:
        adc.setGain(ADS1014Full.PGA_6_144)

        then:
        adc.gain == 0
        adc.fullScale() == 6.144d
        Math.abs(adc.lsb() - 0.003d) < 1e-12

        when:
        adc.setGain(ADS1014Full.PGA_0_256)
        m.words[0] = 0x4000

        then:
        Math.abs(adc.lsb() - 0.000125d) < 1e-12
        Math.abs(adc.readVoltage() - 0.256d * 0x400 / 2048.0d) < 1e-9
        m.lastConfigWrite() == (0x8000 | (5 << 9) | 0x0100 | (4 << 5) | 0x3)

        when:
        adc.setGain(bad)

        then:
        thrown(IllegalArgumentException)

        when:
        adc.setDataRate(ADS1014Full.DR_128)

        then:
        adc.dataRateSps() == 128

        when:
        adc.setDataRate(ADS1014Full.DR_3300)

        then:
        adc.dataRateSps() == 3300
        adc.dataRate == 6

        when:
        adc.setDataRate(7)

        then:
        thrown(IllegalArgumentException)

        where:
        bad << [6]
    }

    def "continuous mode"() {
        given:
        def m = new WordMock()
        def adc = new ADS1014Full(m)

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
    }

    def "comparator and thresholds"() {
        given:
        def m = new WordMock()
        def adc = new ADS1014Full(m)

        when:
        adc.configureComparator(ADS1014Full.COMP_WINDOW, ADS1014Full.ACTIVE_HIGH, true, ADS1014Full.QUEUE_4)

        then:
        m.lastConfigWrite() == (0x0580 | 0x10 | 0x08 | 0x04 | 0x02)

        when:
        adc.disableComparator()

        then:
        (m.lastConfigWrite() & 0x3) == 3

        when:
        adc.configureComparator(0, 0, false, 3)

        then:
        thrown(IllegalArgumentException)

        when:
        adc.setThresholds(-0.5d, 1.0d)

        then:
        m.words[2] == ((-500 & 0xFFF) << 4)
        m.words[3] == (1000 << 4)
        adc.getThresholdsRaw() == [-500, 1000] as int[]

        when:
        adc.setThresholds(-10.0d, 10.0d)

        then:
        adc.getThresholdsRaw() == [-2048, 2047] as int[]

        when:
        adc.setThresholds(1.0d, 1.0d)

        then:
        thrown(IllegalArgumentException)

        when:
        adc.setHighThresholdRaw(2048)

        then:
        thrown(IllegalArgumentException)

        when:
        m.log.clear()
        adc.clearAlert()

        then:
        m.log.size() == 1
        m.log[0] == [0] as byte[]

        when:
        adc.reset()

        then:
        m.words[1] == 0x8583
        m.words[2] == 0x8000
        m.words[3] == 0x7FFF
    }

    def "interrupt routing"() {
        given:
        def m = new WordMock()
        def pin = new FakePin()
        m.pin = pin
        def adc = new ADS1014Full(m)

        when:
        adc.enableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY)

        then:
        m.words[3] == 0x8000
        m.words[2] == 0x0000
        (m.words[1] & 0x3) == 0
        adc.pollInterrupt() == 0

        when:
        pin.high = false

        then:
        adc.pollInterrupt() == ADS1014Full.SOURCE_CONVERSION_READY

        when:
        adc.setHighThresholdRaw(-5)
        adc.setLowThresholdRaw(10)
        adc.enableInterrupt(ADS1014Full.SOURCE_COMPARATOR)

        then:
        thrown(IllegalArgumentException)

        when:
        adc.enableInterrupt(0x04)

        then:
        thrown(IllegalArgumentException)

        when:
        adc.setThresholds(-0.1d, 0.1d)
        adc.configureComparator(ADS1014Full.COMP_TRADITIONAL, ADS1014Full.ACTIVE_LOW, true, ADS1014Full.QUEUE_1)
        adc.enableInterrupt(ADS1014Full.SOURCE_COMPARATOR)
        m.log.clear()

        then:
        adc.getThresholdsRaw() == [-100, 100] as int[]
        adc.pollInterrupt() == ADS1014Full.SOURCE_COMPARATOR
        m.log.any { byte[] b -> b.length == 1 && b[0] == 0 }

        when:
        def got = []
        adc.onInterrupt({ int s -> got.add(s) } as java.util.function.IntConsumer)
        pin.handler.onEdge()

        then:
        pin.trigger == EdgeTrigger.FALLING
        got == [ADS1014Full.SOURCE_COMPARATOR]

        when:
        adc.offInterrupt()
        adc.disableInterrupt(ADS1014Full.SOURCE_COMPARATOR)

        then:
        pin.handler == null
        (m.words[1] & 0x3) == 3
        adc.pollInterrupt() == 0
    }

    def "without a pin conversion-ready falls back to CONFIG.OS"() {
        given:
        def m = new WordMock()
        def adc = new ADS1014Full(m)

        when:
        adc.enableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY)
        m.words[1] = m.words[1] | 0x8000   // the real chip reads OS = 1 when idle

        then:
        adc.pollInterrupt() == ADS1014Full.SOURCE_CONVERSION_READY

        when:
        m.words[1] = m.words[1] & 0x7FFF

        then:
        adc.pollInterrupt() == 0
    }
}
