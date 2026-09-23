package it.uhde.periph.chips.temperature

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class TMP117Spec extends Specification {

    /**
     * Word-addressed variant of {@link MockConnection}: TMP117 registers are
     * 16 bits wide at consecutive pointer values, which would overlap in the
     * shared mock's byte-slot model. Each pointer owns one 16-bit word.
     */
    static class WordMock extends MockConnection {
        final Map<Integer, Integer> words = new ConcurrentHashMap<>(
                [(0x00): 0x8000, (0x01): 0x0220, (0x02): 0x6000, (0x03): 0x8000, (0x0F): 0x1117])
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

        byte[] lastWriteTo(int reg) {
            log.findAll { byte[] b -> b.length >= 2 && (b[0] & 0xFF) == reg }.last()
        }
    }

    def "identity check reads only DEVICE_ID and rejects a wrong ID"() {
        given:
        def m = new WordMock()

        when:
        new TMP117Minimal(m)

        then:
        m.log.size() == 1
        m.log[0] == [0x0F] as byte[]

        when:
        def rev = new WordMock()
        rev.words[0x0F] = 0x2117
        new TMP117Minimal(rev)

        then:
        noExceptionThrown()

        when:
        def bad = new WordMock()
        bad.words[0x0F] = 0x0118
        new TMP117Full(bad)

        then:
        thrown(IOException)
    }

    def "temperature decoding"() {
        given:
        def m = new WordMock()
        def s = new TMP117Minimal(m)

        when:
        m.words[0x00] = raw

        then:
        s.readTemperature() == celsius

        where:
        raw    | celsius
        0x0C80 | 25.0d
        0xFFFF | -0.0078125d
        0xF380 | -25.0d
        0x8000 | -256.0d
        0x7FFF | 255.9921875d
    }

    def "limits and offset encode, decode, round and clamp"() {
        given:
        def m = new WordMock()
        def s = new TMP117Full(m)

        when:
        s.setHighLimit(30.0d)
        s.setLowLimit(-10.25d)
        s.setTemperatureOffset(-0.5d)

        then:
        m.words[0x02] == 0x0F00
        m.words[0x03] == 0xFAE0
        m.words[0x07] == 0xFFC0
        s.getHighLimit() == 30.0d
        s.getLowLimit() == -10.25d
        s.getTemperatureOffset() == -0.5d

        when:
        s.setLowLimit(0.004d)
        def rounded = m.words[0x03]
        s.setHighLimit(1000.0d)
        s.setLowLimit(-1000.0d)

        then:
        rounded == 0x0001
        m.words[0x02] == 0x7FFF
        m.words[0x03] == 0x8000
    }

    def "conversion configuration"() {
        given:
        def m = new WordMock()
        def s = new TMP117Full(m)

        expect:
        s.getConfig() == new TMP117Full.Config(TMP117Full.Mode.CONTINUOUS, 8, 1.0d)

        when:
        s.configure(TMP117Full.Mode.SHUTDOWN, 64, 16.0d)

        then:
        m.words[0x01] == 0x07E0
        s.getConfig() == new TMP117Full.Config(TMP117Full.Mode.SHUTDOWN, 64, 16.0d)
        s.isShutdown()

        when:
        s.configure(TMP117Full.Mode.CONTINUOUS, 0, 0.01d)
        def fastest = m.words[0x01]
        s.configure(TMP117Full.Mode.CONTINUOUS, 8, 0.3d)
        def nearest = m.words[0x01]
        s.configure(TMP117Full.Mode.ONE_SHOT, 32, 2.0d)

        then:
        fastest == 0x0000
        nearest == 0x0120
        m.words[0x01] == 0x0E40
        s.getConfig().mode() == TMP117Full.Mode.ONE_SHOT

        when: 'MOD=10 reads back as continuous; configure keeps the alert bits'
        m.words[0x01] = 0x0800
        def mod10 = s.getConfig().mode()
        m.words[0x01] = 0xF01C
        s.configure()

        then:
        mod10 == TMP117Full.Mode.CONTINUOUS
        m.words[0x01] == 0x023C

        when:
        s.configure(TMP117Full.Mode.CONTINUOUS, 16, 1.0d)

        then:
        thrown(IllegalArgumentException)

        when:
        m.words[0x01] = 0xE660
        s.triggerOneShot()
        def oneShot = m.words[0x01]
        m.words[0x01] = 0x2220
        def ready = s.isDataReady()
        s.reset()

        then:
        oneShot == 0x0E60
        ready
        m.lastWriteTo(0x01) == [0x01, 0x00, 0x02] as byte[]
    }

    def "EEPROM unlock, busy and scratch registers"() {
        given:
        def m = new WordMock()
        def s = new TMP117Full(m)
        m.words[0x05] = 0x1111
        m.words[0x06] = 0x2222
        m.words[0x08] = 0x3333

        when:
        s.unlockEeprom()
        def unlocked = m.words[0x04]
        s.lockEeprom()

        then:
        unlocked == 0x8000
        m.words[0x04] == 0x0000

        when:
        m.words[0x04] = 0x4000

        then:
        s.isEepromBusy()
        s.readEepromScratch(1) == 0x1111
        s.readEepromScratch(2) == 0x2222
        s.readEepromScratch(3) == 0x3333

        when:
        s.writeEepromScratch(2, 0xBEEF)

        then:
        m.words[0x06] == 0xBEEF

        when:
        s.readEepromScratch(4)

        then:
        thrown(IllegalArgumentException)

        when:
        s.writeEepromScratch(1, 0)

        then:
        thrown(IllegalArgumentException)
        m.words[0x05] == 0x1111
    }

    def "alert configuration, poll and onInterrupt"() {
        given:
        def m = new WordMock()
        def s = new TMP117Full(m)

        when:
        s.configureAlert(TMP117Full.AlertMode.THERM, TMP117Full.AlertPolarity.ACTIVE_HIGH,
                TMP117Full.AlertPinFunction.DATA_READY)
        def therm = m.words[0x01]
        s.configureAlert()

        then:
        therm == 0x023C
        m.words[0x01] == 0x0220

        when:
        def masks = [0x2220, 0x8220, 0x4220, 0xC220].collect { int raw ->
            m.words[0x01] = raw
            s.pollInterrupt()
        }

        then:
        masks == [0, TMP117Full.SOURCE_HIGH, TMP117Full.SOURCE_LOW, TMP117Full.SOURCE_HIGH | TMP117Full.SOURCE_LOW]

        when: 'no intPin: the callback fires only when the mask changes'
        m.words[0x01] = 0x0220
        def calls = new CopyOnWriteArrayList<Integer>()
        s.onInterrupt({ int st -> calls.add(st) } as java.util.function.IntConsumer, null)
        Thread.sleep(30)
        def quiet = calls.isEmpty()
        m.words[0x01] = 0x8220
        long deadline = System.currentTimeMillis() + 1000
        while (calls.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        s.offInterrupt()

        then:
        quiet
        calls == [TMP117Full.SOURCE_HIGH]
    }
}
