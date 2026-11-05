package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ADS1115Spec extends Specification {

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

    def "minimal single-ended reads write the whole Config word"() {
        given:
        def m = new WordMock()
        def adc = new ADS1115Minimal(m)

        expect:
        m.log.isEmpty()

        when:
        m.words[0] = 0x4000
        def a = adc.readRaw()

        then:
        a == 0x4000
        m.lastConfig() == 0xC583

        when:
        m.words[0] = 0xFFFF
        def b = adc.readRaw(2)

        then:
        b == -1
        m.lastConfig() == 0xE583

        when:
        adc.readRaw(9)

        then:
        m.lastConfig() == 0xF583

        when:
        m.words[0] = 0x4000

        then:
        adc.readVoltage() == 0x4000 * 0.0000625d
    }

    def "full gain, data rate, differential and continuous"() {
        given:
        def m = new WordMock()
        def adc = new ADS1115Full(m)

        when:
        adc.setGain(ADS1115Full.PGA_4_096)
        m.words[0] = 0x2000

        then:
        adc.fullScale() == 4.096d
        adc.lsb() == 4.096d / 32768
        adc.readVoltage(1) == 0x2000 * (4.096d / 32768)
        m.lastConfig() == 0xD383

        when:
        adc.setGain(6)

        then:
        thrown(IllegalArgumentException)

        when:
        adc.setDataRate(ADS1115Full.DR_860)
        adc.readRaw()

        then:
        adc.getDataRate() == 7
        adc.dataRateSps() == 860
        m.lastConfig() == 0xC3E3

        when:
        def d = adc.readDifferentialRaw(ADS1115Full.DIFF_2_3)

        then:
        d == 0x2000
        m.lastConfig() == 0xB3E3

        when:
        adc.readDifferentialRaw(4)

        then:
        thrown(IllegalArgumentException)

        when:
        adc.startContinuous(ADS1115Full.MUX_AIN1_GND)
        m.words[0] = 0x0100

        then:
        m.lastConfig() == 0x52E3
        adc.readContinuousRaw() == 256

        when:
        adc.stopContinuous()
        m.words[1] = 0x0583

        then:
        m.lastConfig() == 0x53E3
        adc.isConverting()

        when:
        m.words[1] = 0x8583

        then:
        !adc.isConverting()
        adc.readConfig() == 0x8583
    }

    def "thresholds and comparator"() {
        given:
        def m = new WordMock()
        def adc = new ADS1115Full(m)

        when:
        adc.setThresholds(0.5d, 3.0d)

        then:
        m.words[2] == 8000
        m.words[3] == 0x7FFF

        when:
        adc.setHighThresholdRaw(-100)
        adc.setLowThresholdRaw(-200)

        then:
        m.words[3] == 0xFF9C
        adc.getThresholdsRaw() == [-200, -100] as int[]

        when:
        adc.setThresholds(1.0d, 1.0d)

        then:
        thrown(IllegalArgumentException)

        when:
        adc.setThresholds(0.5d, 1.5d)
        adc.configureComparator(ADS1115Full.COMP_WINDOW, ADS1115Full.ACTIVE_HIGH, true, ADS1115Full.QUEUE_2)

        then:
        m.lastConfig() == (0x0400 | 0x0100 | 0x0080 | 0x0010 | 0x0008 | 0x0004 | 0x0001)

        when:
        adc.disableComparator()
        m.log.clear()
        adc.clearAlert()

        then:
        (m.lastConfig() & 3) == 0 // log cleared; the earlier disable is no longer visible
        m.log.size() == 1
        m.log[0][0] == 0
    }

    def "interrupt routing and polling"() {
        given:
        def m = new WordMock()
        def adc = new ADS1115Full(m)

        when:
        adc.enableInterrupt(ADS1115Full.SOURCE_CONVERSION_READY)

        then:
        m.words[3] == 0x8000
        m.words[2] == 0x0000
        (m.lastConfig() & 3) == 0

        when:
        m.words[1] = 0x8583
        def ready = adc.pollInterrupt()
        m.words[1] = 0x0583
        def idle = adc.pollInterrupt()

        then:
        ready == ADS1115Full.SOURCE_CONVERSION_READY
        idle == 0

        when:
        def pin = new FakePin(false)
        m.pin = pin

        then:
        adc.pollInterrupt() == ADS1115Full.SOURCE_CONVERSION_READY

        when:
        pin.high = true

        then:
        adc.pollInterrupt() == 0

        when:
        adc.disableInterrupt(ADS1115Full.SOURCE_CONVERSION_READY)

        then:
        (m.lastConfig() & 3) == 3
        adc.pollInterrupt() == 0

        when:
        adc.setThresholds(0.5d, 1.5d)
        adc.enableInterrupt(ADS1115Full.SOURCE_COMPARATOR)
        pin.high = false

        then:
        (m.lastConfig() & 3) == 0
        adc.pollInterrupt() == ADS1115Full.SOURCE_COMPARATOR

        when:
        adc.enableInterrupt(0x80)

        then:
        thrown(IllegalArgumentException)

        when:
        adc.setGain(0)
        adc.reset()

        then:
        m.words[1] == 0x8583
        m.words[2] == 0x8000
        m.words[3] == 0x7FFF
        adc.getGain() == 2
        adc.getDataRate() == 4
    }
}
