package it.uhde.periph.chips.gas

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Rrh62000Spec extends Specification {

    static final int[] WORDS = [1234, 1000, 500, 100, 20, 52, 125, 203, 61, 140, 230, 2345, 4567, 30, 812, 215, 0] as int[]

    static byte[] b(int... v) {
        byte[] r = new byte[v.length]
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i]
        r
    }

    static int[] block(int status, int[] words) {
        byte[] r = new byte[37]
        r[0] = (byte) status
        r[1] = (byte) (status >> 8)
        for (int i = 0; i < 17; i++) {
            r[2 + 2 * i] = (byte) (words[i] >> 8)
            r[3 + 2 * i] = (byte) words[i]
        }
        r[36] = (byte) Rrh62000Minimal.crc8(r, 36)
        int[] out = new int[37]
        for (int i = 0; i < 37; i++) out[i] = r[i] & 0xFF
        out
    }

    static MockConnection mock() {
        MockConnection c = new MockConnection()
        c.setRegister(0x75, 2, 7)
        c
    }

    static Rrh62000Full full(MockConnection c) {
        Rrh62000Full s = new Rrh62000Full(c, 0)
        s.init()
        s
    }

    static byte[] last(MockConnection c) { c.writes().get(c.writes().size() - 1) }

    static boolean near(double a, double b) { Math.abs(a - b) < 1e-9 }

    def "CRC-8 matches the datasheet"() {
        expect:
        Rrh62000Minimal.crc8(b(0xBE, 0xEF), 2) == 0x92
        Rrh62000Minimal.crc8(b(0, 0), 2) == 0x81
    }

    def "init reads the firmware version and read decodes the main values"() {
        given:
        MockConnection c = mock()
        Rrh62000Minimal s = new Rrh62000Minimal(c, 0)

        when:
        s.init()
        then:
        last(c) == b(0x75)

        when:
        c.setRegister(0x40, 1)
        c.setRegister(0x00, block(5, WORDS))
        def m = s.read()
        int n = c.writes().size()
        then:
        near(m.pm1UgM3(), 5.2) && near(m.pm2_5UgM3(), 12.5) && near(m.pm10UgM3(), 20.3)
        near(m.temperatureC(), 23.45) && near(m.humidityRh(), 45.67)
        m.tvoc() == 300 && m.eco2Ppm() == 812 && near(m.iaq(), 2.15)
        c.writes().get(n - 2) == b(0x40)
        c.writes().get(n - 1) == b(0x00)
    }

    def "full block, status and particles"() {
        given:
        MockConnection c = mock()
        Rrh62000Full s = full(c)
        c.setRegister(0x40, 1)
        c.setRegister(0x00, block(5, WORDS))

        when:
        def d = s.readAll()
        then:
        d.status() == new Rrh62000Minimal.Status(5, true, false, true, false)
        near(d.particles().nc0_3(), 123.4) && near(d.particles().nc4(), 2.0)
        near(d.particles().pm1SmokeUgM3(), 6.1) && near(d.particles().pm10SmokeUgM3(), 23.0)

        when:
        int[] neg = new int[17]
        neg[11] = 0xFE00
        c.setRegister(0x00, block(2, neg))
        int before = c.writes().size()
        def st = s.readStatus()
        then:
        st.dustAccumulation() && !st.highConcentration() && st.raw() == 2
        c.writes().size() == before + 1
        near(s.readAll().temperatureC(), -5.12)

        when:
        c.setRegister(0x00, block(0, WORDS))
        def p = s.readParticles()
        then:
        near(p.pm2_5SmokeUgM3(), 14.0) && near(p.nc1(), 50.0)
    }

    def "CRC error, start-up block and timeout are rejected"() {
        given:
        MockConnection c = mock()
        Rrh62000Full s = full(c)
        c.setRegister(0x40, 1)
        int[] bad = block(0, WORDS)
        bad[36] ^= 0xFF
        c.setRegister(0x00, bad)

        when:
        s.read()
        then:
        thrown(Rrh62000Minimal.Rrh62000Exception)

        when:
        int[] ff = new int[37]
        Arrays.fill(ff, 0xFF)
        c.setRegister(0x00, ff)
        s.read()
        then:
        def e = thrown(Rrh62000Minimal.Rrh62000Exception)
        e.message.contains('start-up')

        when:
        c.setRegister(0x40, 0)
        c.setRegister(0x00, block(0, WORDS))
        s.dataTimeoutMs = 0
        s.read()
        then:
        thrown(Rrh62000Minimal.Rrh62000Exception)
    }

    def "commands write their codes"() {
        given:
        MockConnection c = mock()
        Rrh62000Full s = full(c)

        when:
        c.setRegister(0x40, 0)
        then:
        !s.dataReady()

        when:
        c.setRegister(0x40, 1)
        then:
        s.dataReady()

        when:
        s.sleep()
        then:
        last(c) == b(0x50, 0x00)

        when:
        s.wake()
        then:
        last(c) == b(0x50, 0x80)

        when:
        s.startCleaning()
        c.setRegister(0x74, 1)
        then:
        last(c) == b(0x51, 0x01)
        s.cleaningDone()

        when:
        s.softReset()
        then:
        last(c) == b(0x52, 0x81)

        when:
        c.failNextWrites(1, 0)
        s.softReset()
        then:
        noExceptionThrown()
    }

    def "settings round-trip and range checks"() {
        given:
        MockConnection c = mock()
        Rrh62000Full s = full(c)

        when:
        s.setMovingAverage(30)
        s.setFanSpeed(70)
        s.setCleaningInterval(86400)
        s.setCleaningTime(20)
        then:
        s.getMovingAverage() == 30
        s.getFanSpeed() == 70
        c.registers().get(0x5A) == 0x0B && c.registers().get(0x5B) == 0x40
        s.getCleaningInterval() == 86400
        s.getCleaningTime() == 20

        when:
        call.call()
        then:
        thrown(IllegalArgumentException)

        where:
        call << [{ -> new Rrh62000Full(new MockConnection(), 0).setMovingAverage(0) },
                 { -> new Rrh62000Full(new MockConnection(), 0).setMovingAverage(61) },
                 { -> new Rrh62000Full(new MockConnection(), 0).setFanSpeed(59) },
                 { -> new Rrh62000Full(new MockConnection(), 0).setFanSpeed(101) },
                 { -> new Rrh62000Full(new MockConnection(), 0).setCleaningInterval(31) },
                 { -> new Rrh62000Full(new MockConnection(), 0).setCleaningInterval(1814430) },
                 { -> new Rrh62000Full(new MockConnection(), 0).setCleaningTime(61) }]
    }

    def "diagnostics and identification"() {
        given:
        MockConnection c = mock()
        Rrh62000Full s = full(c)
        c.setRegister(0x71, 0x00, 0x01, 0xE2, 0x40)

        expect:
        s.readMoxResistance() == 123456L

        when:
        c.setRegister(0x72, 1, 2, 3, 4, 5, 6)
        then:
        s.uniqueId() == b(1, 2, 3, 4, 5, 6)

        when:
        c.setRegister(0x73, 3, 1, 4)
        then:
        s.algorithmVersion() == [3, 1, 4] as int[]

        when:
        c.setRegister(0x75, 2, 7)
        then:
        s.firmwareVersion() == [2, 7] as int[]
    }
}
