package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class LC709203FSpec extends Specification {

    /** Each command code holds one (low, high, crc) word; writes do not overlap neighbours. */
    static class WordMock extends MockConnection {
        final Map<Integer, byte[]> words = [:]

        void put(int cmd, int value) {
            int lo = value & 0xFF
            int hi = (value >> 8) & 0xFF
            words[cmd] = [(byte) lo, (byte) hi, (byte) LC709203FMinimal.crc8(0x16, cmd, 0x17, lo, hi)] as byte[]
        }

        @Override
        void write(byte[] data) throws IOException {
            writes().add(data.clone())
            if (data.length == 4) words[data[0] & 0xFF] = Arrays.copyOfRange(data, 1, 4)
        }

        @Override
        byte[] writeRead(byte[] data, int n) throws IOException {
            writes().add(data.clone())
            byte[] w = words[data[0] & 0xFF]
            w == null ? new byte[n] : w.clone()
        }

        boolean wrote(int cmd, int lo, int hi) {
            writes().any { byte[] w -> w.length == 4 && (w[0] & 0xFF) == cmd && (w[1] & 0xFF) == lo && (w[2] & 0xFF) == hi }
        }

        boolean lastIs(int cmd, int lo, int hi) {
            byte[] w = writes().last()
            w.length == 4 && (w[0] & 0xFF) == cmd && (w[1] & 0xFF) == lo && (w[2] & 0xFF) == hi &&
                (w[3] & 0xFF) == LC709203FMinimal.crc8(0x16, cmd, lo, hi)
        }
    }

    def "CRC datasheet examples"() {
        expect:
        LC709203FMinimal.crc8(0x16, 0x09, 0x17, 0xC2, 0x0E) == 0x86
        LC709203FMinimal.crc8(0x16, 0x09, 0x55, 0xAA) == 0x3B
    }

    def "APA interpolation"() {
        expect:
        LC709203FMinimal.apaFor(700) == 0x14
        LC709203FMinimal.apaFor(50) == 0x08
        LC709203FMinimal.apaFor(9000) == 0x36
        LC709203FMinimal.apaFor(1000) == 0x19
    }

    def "full API"() {
        given:
        def conn = new WordMock()
        conn.put(0x13, 8)
        conn.put(0x14, 0)

        when: "construction runs the starting flow"
        def sensor = new LC709203FFull(conn)

        then:
        conn.wrote(0x15, 0x01, 0x00)
        conn.wrote(0x0B, 0x19, 0x00)
        conn.wrote(0x12, 0x01, 0x00)
        conn.wrote(0x16, 0x00, 0x00)
        conn.wrote(0x08, 0xA6, 0x0B)

        when:
        conn.put(0x09, 3778)
        conn.put(0x0F, 755)
        conn.put(0x08, 0x0BA6)

        then:
        Math.abs(sensor.voltage() - 3.778d) < 1e-9d
        Math.abs(sensor.percent() - 75.5d) < 1e-9d
        Math.abs(sensor.temperature() - 25.0d) < 0.06d

        when: "bad CRC"
        conn.words[0x09] = [(byte) 0xC2, (byte) 0x0E, (byte) 0x00] as byte[]
        sensor.voltage()

        then:
        thrown(IOException)

        when:
        sensor.setTemperature(100d)

        then:
        conn.lastIs(0x08, 0x04, 0x0D)

        when:
        sensor.setTemperature(-50d)

        then:
        conn.lastIs(0x08, 0xE4, 0x09)

        when:
        conn.put(0x0D, 42)

        then:
        sensor.rsoc() == 42

        when:
        sensor.setRsoc(55)

        then:
        conn.lastIs(0x0D, 55, 0)

        when:
        sensor.initRsoc()

        then:
        conn.lastIs(0x07, 0x55, 0xAA)

        when:
        sensor.beforeRsoc()

        then:
        conn.lastIs(0x04, 0x55, 0xAA)

        when:
        sensor.useThermistor(3380)

        then:
        conn.wrote(0x06, 0x34, 0x0D)
        conn.lastIs(0x16, 1, 0)

        when:
        conn.put(0x16, 1)

        then:
        sensor.isThermistorMode()

        when:
        sensor.useI2cTemperature(25.0d)

        then:
        conn.lastIs(0x08, 0xA6, 0x0B)

        when:
        sensor.setCurrentDirection(LC709203FFull.DIRECTION_DISCHARGE)

        then:
        conn.lastIs(0x0A, 0xFF, 0xFF)

        when:
        sensor.setCurrentDirection(5)

        then:
        thrown(IllegalArgumentException)

        when:
        sensor.setApa(0x2D)

        then:
        conn.lastIs(0x0B, 0x2D, 0)

        when:
        sensor.setApt(0x1234)

        then:
        conn.lastIs(0x0C, 0x34, 0x12)

        when:
        sensor.setProfile(0)

        then:
        conn.wrote(0x12, 0, 0)
        conn.lastIs(0x15, 1, 0)

        when:
        conn.put(0x1A, 0x0301)
        conn.put(0x11, 0x1234)

        then:
        sensor.profileId() == 0x0301
        sensor.icVersion() == 0x1234

        when:
        sensor.sleep()

        then:
        conn.lastIs(0x15, 2, 0)

        when:
        conn.put(0x15, 2)

        then:
        sensor.isSleeping()

        when:
        conn.put(0x15, 1)

        then:
        !sensor.isSleeping()

        when:
        sensor.setAlarmRsoc(20)
        sensor.setAlarmVoltage(3.5d)

        then:
        conn.lastIs(0x14, 0xAC, 0x0D)

        when:
        conn.put(0x0D, 10)
        conn.put(0x09, 3400)

        then:
        sensor.pollInterrupt() == 3

        when:
        conn.put(0x0D, 50)
        conn.put(0x09, 3900)

        then:
        sensor.pollInterrupt() == 0
    }
}
