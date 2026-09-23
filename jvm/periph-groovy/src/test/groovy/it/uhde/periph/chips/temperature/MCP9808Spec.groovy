package it.uhde.periph.chips.temperature

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class MCP9808Spec extends Specification {

    /**
     * Word-addressed variant of {@link MockConnection}: MCP9808 registers are
     * 16 bits wide at consecutive pointer values (MANUFACTURER_ID at 0x06,
     * DEVICE_ID at 0x07), which would overlap in the shared mock's byte-slot
     * model. Each pointer owns one 16-bit word; 1-byte accesses use the
     * word's low byte.
     */
    static class WordMock extends MockConnection {
        final Map<Integer, Integer> words = new ConcurrentHashMap<>([(0x06): 0x0054, (0x07): 0x0400, (0x08): 0x0003])
        final List<byte[]> log = new CopyOnWriteArrayList<>()

        @Override
        void write(byte[] data) throws IOException {
            log.add(data.clone())
            if (data.length == 3) words[data[0] & 0xFF] = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF)
            else if (data.length == 2) words[data[0] & 0xFF] = data[1] & 0xFF
        }

        @Override
        byte[] writeRead(byte[] data, int n) throws IOException {
            log.add(data.clone())
            int w = words.getOrDefault(data[0] & 0xFF, 0)
            return n == 1 ? [(byte) w] as byte[] : [(byte) (w >> 8), (byte) w] as byte[]
        }

        List<byte[]> writesTo(int reg) {
            log.findAll { byte[] b -> b.length >= 2 && (b[0] & 0xFF) == reg }
        }
    }

    def "identity check passes without register writes and rejects wrong IDs"() {
        given:
        def m = new WordMock()

        when:
        new MCP9808Minimal(m)

        then:
        m.log.every { byte[] b -> b.length == 1 }

        when:
        def bad = new WordMock()
        bad.words[0x06] = 0x1234
        new MCP9808Minimal(bad)

        then:
        thrown(IOException)

        when:
        def badDev = new WordMock()
        badDev.words[0x07] = 0x0500
        new MCP9808Full(badDev)

        then:
        thrown(IOException)

        when:
        def rev = new WordMock()
        rev.words[0x07] = 0x0401
        new MCP9808Minimal(rev)

        then:
        noExceptionThrown()
    }

    def "temperature decoding"() {
        given:
        def m = new WordMock()
        def s = new MCP9808Minimal(m)

        when:
        m.words[0x05] = raw

        then:
        s.readTemperature() == expected

        where:
        raw    || expected
        0x0194 || 25.25d
        0xE194 || 25.25d   // flag bits masked
        0x1FF0 || -1.0d
        0x1E6C || -25.25d
        0x0001 || 0.0625d
    }

    def "boundaries encode, decode, round and clamp"() {
        given:
        def m = new WordMock()
        def s = new MCP9808Full(m)

        when:
        s.setUpperLimit(80.0d)
        s.setLowerLimit(-25.0d)

        then:
        m.words[0x02] == 0x0500
        s.getUpperLimit() == 80.0d
        m.words[0x03] == 0x1E70
        s.getLowerLimit() == -25.0d

        when:
        s.setCriticalLimit(-5.1d)

        then:
        s.getCriticalLimit() == -5.0d

        when:
        s.setCriticalLimit(22.13d)

        then:
        s.getCriticalLimit() == 22.25d

        when:
        s.setUpperLimit(1000.0d)
        s.setLowerLimit(-1000.0d)

        then:
        s.getUpperLimit() == 255.75d
        s.getLowerLimit() == -256.0d
    }

    def "resolution and hysteresis"() {
        given:
        def m = new WordMock()
        def s = new MCP9808Full(m)

        when:
        s.setResolution(0.25d)

        then:
        m.writesTo(0x08).last() == [0x08, 0x01] as byte[]
        s.getResolution() == 0.25d

        when:
        def before = m.writesTo(0x08).size()
        s.setResolution(0.3d)

        then:
        thrown(IllegalArgumentException)
        m.writesTo(0x08).size() == before

        when:
        m.words[0x01] = 0x0000
        s.setHysteresis(3.0d)

        then:
        m.words[0x01] == 0x0400
        s.getHysteresis() == 3.0d

        when:
        s.setHysteresis(2.0d)

        then:
        thrown(IllegalArgumentException)
    }

    def "shutdown, locks and Alert configuration"() {
        given:
        def m = new WordMock()
        def s = new MCP9808Full(m)

        when:
        m.words[0x01] = 0x0400
        s.shutdown()

        then:
        m.words[0x01] == 0x0500
        s.isShutdown()

        when:
        s.wake()

        then:
        m.words[0x01] == 0x0400
        !s.isShutdown()

        when:
        m.words[0x01] = 0x0080
        def before = m.writesTo(0x01).size()
        s.shutdown()

        then: 'no-op while locked'
        m.writesTo(0x01).size() == before

        when:
        m.words[0x01] = 0x0000
        s.lockCriticalLimit()

        then:
        m.words[0x01] == 0x0080
        s.isCriticalLimitLocked()
        !s.isWindowLimitsLocked()

        when:
        m.words[0x01] = 0x0000
        s.lockWindowLimits()

        then:
        m.words[0x01] == 0x0040
        s.isWindowLimitsLocked()

        when:
        m.words[0x01] = 0x0000
        s.configureAlert(MCP9808Full.AlertMode.CRITICAL_ONLY, MCP9808Full.AlertOutput.INTERRUPT,
                MCP9808Full.AlertPolarity.ACTIVE_HIGH)

        then:
        m.words[0x01] == 0x0007

        when:
        s.configureAlert()

        then:
        m.words[0x01] == 0x0000

        when:
        m.words[0x01] = 0x0040
        s.configureAlert(MCP9808Full.AlertMode.ALL, MCP9808Full.AlertOutput.INTERRUPT,
                MCP9808Full.AlertPolarity.ACTIVE_LOW)

        then:
        thrown(IllegalStateException)
        m.words[0x01] == 0x0040

        when:
        m.words[0x01] = 0x0000
        s.enableAlert()

        then:
        m.words[0x01] == 0x0008

        when:
        s.disableAlert()

        then:
        m.words[0x01] == 0x0000

        when:
        m.words[0x01] = 0x0019
        def asserted = s.isAlertAsserted()
        s.clearInterrupt()

        then:
        asserted
        m.writesTo(0x01).last() == [0x01, 0x00, 0x29] as byte[]
    }

    def "pollInterrupt and the polling fallback"() {
        given:
        def m = new WordMock()
        def s = new MCP9808Full(m)

        expect:
        [[0x0194, 0], [0x2194, MCP9808Full.SOURCE_LOWER],
         [0xC194, MCP9808Full.SOURCE_UPPER | MCP9808Full.SOURCE_CRITICAL]].every { List<Integer> c ->
            m.words[0x05] = c[0]
            s.pollInterrupt() == c[1]
        }

        when: 'no intPin: the callback fires only when the mask changes'
        m.words[0x05] = 0x0194
        def calls = new CopyOnWriteArrayList<Integer>()
        s.onInterrupt({ int st -> calls.add(st) } as java.util.function.IntConsumer)
        Thread.sleep(30)
        def quiet = calls.isEmpty()
        m.words[0x05] = 0x4194
        long deadline = System.currentTimeMillis() + 1000
        while (calls.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        s.offInterrupt()

        then:
        quiet
        calls == [MCP9808Full.SOURCE_UPPER]
    }
}
