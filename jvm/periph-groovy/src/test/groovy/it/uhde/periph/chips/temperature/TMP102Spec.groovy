package it.uhde.periph.chips.temperature

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.DoubleConsumer

class TMP102Spec extends Specification {

    static final int REG_TEMP = 0x00
    static final int REG_CONFIG = 0x01
    static final int REG_T_HIGH = 0x03

    /**
     * Word-addressed variant of {@link MockConnection}: TMP102 registers are 16 bits wide at
     * consecutive pointer values. Each pointer owns one 16-bit word; {@code osDone} controls
     * whether CONFIGURATION reads back with OS = 1.
     */
    static class WordMock extends MockConnection {
        final Map<Integer, Integer> words = new ConcurrentHashMap<>([(0x00): 0x0000, (0x01): 0x60A0, (0x02): 0x4B00, (0x03): 0x5000])
        final List<byte[]> log = new CopyOnWriteArrayList<>()
        volatile boolean osDone = true

        @Override
        void write(byte[] data) throws IOException {
            log.add(data.clone())
            if (data.length == 3) words[data[0] & 0xFF] = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF)
        }

        @Override
        byte[] writeRead(byte[] data, int n) throws IOException {
            log.add(data.clone())
            int w = words.getOrDefault(data[0] & 0xFF, 0)
            if ((data[0] & 0xFF) == 0x01) w = osDone ? (w | 0x8000) : (w & 0x7FFF)
            return [(byte) (w >> 8), (byte) w] as byte[]
        }

        List<byte[]> writesTo(int reg) {
            log.findAll { it.length == 3 && (it[0] & 0xFF) == reg }
        }
    }

    def "constructor makes no bus access"() {
        given:
        def m = new WordMock()

        when:
        new TMP102Minimal(m)
        new TMP102Full(m)

        then:
        m.log.isEmpty()
    }

    def "temperature decoding"() {
        given:
        def m = new WordMock()
        def s = new TMP102Minimal(m)

        when:
        m.words[REG_TEMP] = raw

        then:
        s.readTemperature() == want

        where:
        raw                                | want
        0x1900                             | 25.0d
        0xE700                             | -25.0d
        0xC900                             | -55.0d
        0x7FF0                             | 127.9375d
        0x4B01                             | 150.0d
        0xCE01                             | -100.0d
    }

    def "config read-modify-write"() {
        given:
        def m = new WordMock()
        def s = new TMP102Full(m)

        expect:
        s.readConfig() == (0x60A0 | 0x8000)

        when:
        s.setConversionRate(8d)

        then:
        (m.words[REG_CONFIG] & 0x00C0) == 0x00C0
        (m.words[REG_CONFIG] & 0x6000) == 0x6000

        when:
        s.setFaultQueue(6)

        then:
        (m.words[REG_CONFIG] & 0x1800) == 0x1800

        when:
        s.setFaultQueue(2)

        then:
        (m.words[REG_CONFIG] & 0x1800) == 0x0800

        when:
        s.setPolarity(true)
        s.setThermostatMode(true)
        s.setExtendedMode(true)
        s.shutdown()

        then:
        (m.words[REG_CONFIG] & 0x0710) == 0x0710

        when:
        s.setPolarity(false)
        s.setThermostatMode(false)
        s.setExtendedMode(false)
        s.wake()

        then:
        (m.words[REG_CONFIG] & 0x0710) == 0

        when:
        s.writeConfig(0xFFFF)

        then:
        (m.words[REG_CONFIG] & 0x602F) == 0x6000
    }

    def "invalid arguments are rejected"() {
        given:
        def s = new TMP102Full(new WordMock())

        when:
        s.setConversionRate(2d)

        then:
        thrown(IllegalArgumentException)

        when:
        s.setFaultQueue(3)

        then:
        thrown(IllegalArgumentException)
    }

    def "limits in normal and extended mode"() {
        given:
        def m = new WordMock()
        def s = new TMP102Full(m)

        when:
        s.setTHigh(30.0d)

        then:
        m.words[REG_T_HIGH] == 0x1E00
        s.getTHigh() == 30.0d

        when:
        s.setTLow(-10.5d)

        then:
        s.getTLow() == -10.5d

        when:
        s.setTHigh(500.0d)

        then:
        s.getTHigh() == 127.9375d

        when:
        s.setExtendedMode(true)
        s.setTHigh(140.0d)

        then:
        m.words[REG_T_HIGH] == (2240 << 3)
        s.getTHigh() == 140.0d

        when:
        s.setTHigh(1000.0d)

        then:
        s.getTHigh() == 150.0d
    }

    def "one-shot restores the mode and times out"() {
        given:
        def m = new WordMock()
        def s = new TMP102Full(m)
        m.words[REG_TEMP] = 0x1900

        expect:
        s.oneShot() == 25.0d
        m.writesTo(REG_CONFIG).any { (it[1] & 0x80) != 0 }
        (m.words[REG_CONFIG] & 0x0100) == 0

        when:
        m.words[REG_CONFIG] = 0x6180
        s.oneShot()

        then:
        (m.words[REG_CONFIG] & 0x0100) == 0x0100

        when:
        m.osDone = false
        s.oneShot()

        then:
        thrown(IOException)
    }

    def "alert state and poll"() {
        given:
        def m = new WordMock()
        def s = new TMP102Full(m)

        when:
        m.words[REG_CONFIG] = cfg

        then:
        s.alertState() == want

        where:
        cfg    | want
        0x6020 | false
        0x6000 | true
        0x6420 | true
        0x6400 | false
    }

    def "poll interrupt reads temperature after config"() {
        given:
        def m = new WordMock()
        def s = new TMP102Full(m)
        m.words[REG_CONFIG] = 0x6000
        m.log.clear()

        expect:
        s.pollInterrupt()
        m.log.last() == [REG_TEMP] as byte[]
    }

    def "general call reset"() {
        given:
        def m = new WordMock()

        when:
        new TMP102Full(m).reset()

        then:
        m.log.size() == 1
        m.log[0] == [0x00, 0x06] as byte[]
    }

    def "polling fallback reports the temperature"() {
        given:
        def m = new WordMock()
        def s = new TMP102Full(m)
        m.words[REG_TEMP] = 0x1900
        m.words[REG_CONFIG] = 0x6000
        def calls = new CopyOnWriteArrayList<Double>()

        when:
        s.onInterrupt({ double t -> calls.add(t) } as DoubleConsumer, null)
        Thread.sleep(300)
        s.offInterrupt()

        then:
        !calls.isEmpty() && calls[0] == 25.0d
    }
}
