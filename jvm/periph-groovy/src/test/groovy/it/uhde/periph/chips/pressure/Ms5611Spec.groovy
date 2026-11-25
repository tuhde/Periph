package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Ms5611Spec extends Specification {

    static final int[] COEFFS = [40127, 36924, 23317, 23282, 33464, 28312] as int[]
    static final int D1 = 9085466
    static final int D2 = 8569150

    MockConnection mock
    Ms5611Full s

    static int[] prom(boolean corrupt = false) {
        int[] w = [0x3132, COEFFS[0], COEFFS[1], COEFFS[2], COEFFS[3], COEFFS[4], COEFFS[5], 0x5A3 << 4] as int[]
        w[7] |= Ms5611Minimal.crc4(w)
        if (corrupt) w[3] ^= 0x0100
        return w
    }

    static void queueProm(MockConnection c, boolean corrupt = false) {
        for (int x : prom(corrupt)) c.queueRead([(byte) (x >> 8), (byte) x] as byte[])
    }

    static void queueAdc(MockConnection c, int v) {
        c.queueRead([(byte) (v >> 16), (byte) (v >> 8), (byte) v] as byte[])
    }

    List<Integer> cmds() {
        return mock.writes().collect { byte[] w -> w[0] & 0xFF }
    }

    def setup() {
        mock = new MockConnection()
        s = new Ms5611Full(mock)
    }

    def ready() {
        queueProm(mock)
        s.init()
        mock.writes().clear()
    }

    def "crc4 behaviour"() {
        expect:
        Ms5611Minimal.crc4(new int[8]) == 0
        Ms5611Minimal.crc4(prom()) == (prom()[7] & 0xF)
        Ms5611Minimal.crc4(prom(true)) != (prom(true)[7] & 0xF)
    }

    def "init sequence and PROM"() {
        expect:
        mock.writes().isEmpty()

        when:
        queueProm(mock)
        s.init()

        then:
        cmds() == [0x1E, 0xA0, 0xA2, 0xA4, 0xA6, 0xA8, 0xAA, 0xAC, 0xAE]
        s.coefficients() == COEFFS
        s.promCrcOk()
        s.serialCode() == 0x5A3
    }

    def "init rejects bad CRC and propagates bus errors"() {
        when:
        queueProm(mock, true)
        s.init()

        then:
        thrown(IOException)

        when:
        mock = new MockConnection()
        s = new Ms5611Full(mock)
        mock.failNextWrites(1, 0)
        s.init()

        then:
        thrown(IOException)
    }

    def "datasheet example and second-order branches"() {
        setup:
        ready()

        expect:
        Math.abs(s.compensate(D1, D2)[0] - 20.07d) < 1e-6
        Math.abs(s.compensate(D1, D2)[1] - 100009.0d) < 0.5
        Math.abs(s.compensate(D1, D2 - 300000)[0] - 9.55d) < 1e-6
        Math.abs(s.compensate(D1, D2 - 300000)[1] - 97972.0d) < 0.5
        Math.abs(s.compensate(D1, D2 - 300000, false)[0] - 9.96d) < 1e-6
        Math.abs(s.compensate(D1, D2 - 300000, false)[1] - 98061.0d) < 0.5
        Math.abs(s.compensate(D1, D2 - 1200000)[0] + 27.09d) < 1e-6
        Math.abs(s.compensate(D1, D2 - 1200000)[1] - 90615.0d) < 0.5
        Math.abs(s.compensate(D1, D2 - 1200000, false)[0] + 20.42d) < 1e-6
        Math.abs(s.compensate(D1, D2 - 1200000, false)[1] - 92219.0d) < 0.5
    }

    def "minimal read sends D2 then D1 and fails on a zero result"() {
        setup:
        queueProm(mock)
        Ms5611Minimal m = new Ms5611Minimal(mock)
        m.init()
        mock.writes().clear()
        queueAdc(mock, D2)
        queueAdc(mock, D1)

        when:
        double[] r = m.read()

        then:
        cmds() == [0x58, 0x00, 0x48, 0x00]
        Math.abs(r[0] - 20.07d) < 1e-6
        Math.abs(r[1] - 100009.0d) < 0.5

        when:
        queueAdc(mock, 0)
        m.read()

        then:
        thrown(IOException)
    }

    def "single values and raw codes"() {
        setup:
        ready()
        queueAdc(mock, D2)
        queueAdc(mock, D1)

        expect:
        Math.abs(s.readPressure() - 100009.0d) < 0.5

        when:
        mock.writes().clear()
        queueAdc(mock, D2)
        double t = s.readTemperature()

        then:
        Math.abs(t - 20.07d) < 1e-6
        cmds() == [0x58, 0x00]

        when:
        queueAdc(mock, D2)
        queueAdc(mock, D1)

        then:
        s.readRaw() == [D1, D2] as int[]
    }

    def "oversampling commands"() {
        setup:
        ready()
        s.setOversampling(osr)
        queueAdc(mock, D2)
        queueAdc(mock, D1)
        s.read()

        expect:
        s.getOversampling() == osr
        cmds()[0] == (0x50 | (idx << 1))
        cmds()[2] == (0x40 | (idx << 1))

        where:
        osr  | idx
        256  | 0
        512  | 1
        1024 | 2
        2048 | 3
        4096 | 4
    }

    def "invalid oversampling is rejected and the default is 4096"() {
        when:
        s.setOversampling(300)

        then:
        thrown(IllegalArgumentException)
        s.getOversampling() == 4096
    }

    def "conversion times"() {
        expect:
        Math.abs(s.conversionTime(256) - 0.0006d) < 1e-9
        Math.abs(s.conversionTime(512) - 0.00117d) < 1e-9
        Math.abs(s.conversionTime(1024) - 0.00228d) < 1e-9
        Math.abs(s.conversionTime(2048) - 0.00454d) < 1e-9
        Math.abs(s.conversionTime(4096) - 0.00904d) < 1e-9
        Math.abs(s.conversionTime() - 0.00904d) < 1e-9
    }

    def "invalid conversion time ratio is rejected"() {
        when:
        s.conversionTime(100)

        then:
        thrown(IllegalArgumentException)
    }

    def "non-blocking conversion"() {
        setup:
        ready()

        when:
        s.startConversion(Ms5611Full.TEMPERATURE)
        s.startConversion(Ms5611Full.PRESSURE, 512)

        then:
        cmds() == [0x58, 0x42]

        when:
        s.startConversion(0, 99)

        then:
        thrown(IllegalArgumentException)

        when:
        queueAdc(mock, D1)

        then:
        s.readAdc() == D1

        when:
        queueAdc(mock, 0)
        s.readAdc()

        then:
        thrown(IOException)
    }

    def "reset reloads the PROM and readProm returns the words"() {
        setup:
        ready()
        queueProm(mock)
        s.reset()

        expect:
        cmds()[0] == 0x1E
        mock.writes().size() == 9

        when:
        queueProm(mock)

        then:
        s.readProm() == prom()
    }

    def "altitude formula"() {
        setup:
        ready()
        queueAdc(mock, D2)
        queueAdc(mock, D1)
        double expected = 44330.0d * (1.0d - Math.pow(100009.0d / 101325.0d, 1.0d / 5.255d))
        double h = s.altitude()

        expect:
        Math.abs(h - expected) < 1e-6
        h > 0

        when:
        queueAdc(mock, D2)
        queueAdc(mock, D1)

        then:
        Math.abs(s.altitude(100009.0d)) < 1e-6
    }
}
