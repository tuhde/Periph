package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class HS40xxSpec extends Specification {

    MockConnection mock
    HS40xxFull s

    static int crc8(int... d) {
        int crc = 0xFF
        for (int b : d) {
            crc ^= b
            for (int i = 0; i < 8; i++) crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x1D) & 0xFF : (crc << 1) & 0xFF
        }
        return crc
    }

    static byte[] b(int... v) {
        byte[] out = new byte[v.length]
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i]
        return out
    }

    static byte[] ht(int h, int t) {
        int[] d = [h >> 8, h & 0xFF, t >> 8, t & 0xFF] as int[]
        return b(d[0], d[1], d[2], d[3], crc8(d))
    }

    static byte[] tOnly(int t) {
        return b(t >> 8, t & 0xFF, crc8(0, 0, t >> 8, t & 0xFF))
    }

    static byte[] withStatus(int status, byte[] frame) {
        byte[] out = new byte[frame.length + 1]
        out[0] = (byte) status
        System.arraycopy(frame, 0, out, 1, frame.length)
        return out
    }

    boolean writesAre(List<byte[]> expected) {
        List<byte[]> w = mock.writes()
        if (w.size() != expected.size()) return false
        for (int i = 0; i < w.size(); i++) {
            if (!Arrays.equals(w[i], expected[i])) return false
        }
        return true
    }

    def setup() {
        mock = new MockConnection()
        s = new HS40xxFull(mock)
    }

    def "crc datasheet example"() {
        expect:
        crc8(0x05, 0x80, 0x05, 0x80) == 0xF2
    }

    def "constructor does no bus access"() {
        expect:
        mock.writes().isEmpty()
    }

    def "aliases extend the drivers"() {
        expect:
        new HS4001Full(mock) instanceof HS40xxFull
        new HS4003Full(mock) instanceof HS40xxMinimal
        new HS4003Minimal(mock) instanceof HS40xxMinimal
        new HS4001Minimal(mock) instanceof HS40xxMinimal
    }

    def "datasheet example frame"() {
        when:
        mock.queueRead(b(0x05, 0x80, 0x05, 0x80, 0xF2))
        double[] m = s.read()

        then:
        writesAre([b(0xF5)])
        Math.abs(m[1] - 8.59d) < 0.01d
        Math.abs(m[0] - (-25.8d)) < 0.05d
    }

    def "full scale and zero"() {
        when:
        mock.queueRead(ht(0x3FFF, 0x3FFF))
        double[] m = s.read()

        then:
        Math.abs(m[1] - 100.0d) < 1e-6d
        Math.abs(m[0] - 125.0d) < 1e-6d

        when:
        mock.queueRead(ht(0, 0))
        m = s.read()

        then:
        Math.abs(m[1]) < 1e-9d
        Math.abs(m[0] + 40.0d) < 1e-9d
    }

    def "nack is retried and a persistent nack fails"() {
        when:
        mock.failNextReads(2)
        mock.queueRead(ht(0x2000, 0x1000))

        then:
        Math.abs(s.read()[1] - 50.0d) < 0.01d

        when:
        mock.failNextReads(1000)
        s.read()

        then:
        thrown(IOException)
    }

    def "crc mismatch is retried once"() {
        given:
        byte[] bad = ht(0x2000, 0x1000)
        bad[4] = (byte) (bad[4] ^ 0xFF)

        when:
        mock.queueRead(bad)
        mock.queueRead(ht(0x2000, 0x1000))

        then:
        Math.abs(s.read()[1] - 50.0d) < 0.01d
        writesAre([b(0xF5), b(0xF5)])

        when:
        mock.queueRead(bad)
        mock.queueRead(bad)
        s.read()

        then:
        thrown(IOException)
    }

    def "single values hold and raw"() {
        when:
        mock.queueRead(tOnly(0x1000))
        double t = s.readTemperature()
        mock.queueRead(ht(0x2000, 0x1000))
        double rh = s.readHumidity()
        mock.queueRead(ht(0x2000, 0x1000))
        double hold = s.readHold()[1]
        mock.queueRead(ht(0x1234, 0x0ABC))
        int[] raw = s.readRaw()

        then:
        Math.abs(t - (4096 / 16383.0d * 165.0d - 40.0d)) < 1e-9d
        Math.abs(rh - 50.0d) < 0.01d
        Math.abs(hold - 50.0d) < 0.01d
        raw[0] == 0x1234
        raw[1] == 0x0ABC
        writesAre([b(0xF3), b(0xF5), b(0xE5), b(0xF5)])
    }

    def "registers"() {
        when:
        mock.queueRead(b(0x5A))
        int v = s.readRegister(0x02)
        s.writeRegister(0x03, 0x0A)

        then:
        v == 0x5A
        writesAre([b(0xA7, 0x02), b(0xA6, 0x03, 0x0A)])
    }

    def "resolution"() {
        when:
        mock.queueRead(b(0xF3))
        s.setResolution(8, 12)

        then:
        writesAre([b(0xA7, 0x00), b(0xA6, 0x00, 0xF2)])

        when:
        mock = new MockConnection()
        s = new HS40xxFull(mock)
        s.setResolution(9, null)

        then:
        thrown(IllegalArgumentException)
        mock.writes().isEmpty()

        when:
        mock.queueRead(b(0x0B))
        int[] r = s.getResolution()

        then:
        r[0] == 12
        r[1] == 14
    }

    def "periodic without alerts"() {
        when:
        mock.queueRead(b(0x00))
        mock.queueRead(b(0x01))
        s.startPeriodic(2.5d)

        then:
        writesAre([b(0xA7, 0x03), b(0xA7, 0x02), b(0xA6, 0x02, 0xA1)])

        when:
        s.startPeriodic(3.0d)

        then:
        thrown(IllegalArgumentException)

        when:
        mock.queueRead(ht(0x2000, 0x1000))
        double[] p = s.readPeriodic()
        s.stopPeriodic()

        then:
        p[2] == 0.0d
        Math.abs(p[1] - 50.0d) < 0.01d
        Arrays.equals(mock.writes().last(), b(0x30))
    }

    def "alerts and status"() {
        when:
        s.pollInterrupt()

        then:
        thrown(IllegalStateException)

        when:
        mock.queueRead(b(0x00))
        s.enableInterrupt(HS40xxFull.SOURCE_RH_HIGH)

        then:
        writesAre([b(0xA7, 0x03), b(0xA6, 0x03, 0x02)])

        when:
        mock.queueRead(withStatus(0x0A, ht(0x2000, 0x1000)))
        int status = (int) s.readPeriodic()[2]
        mock.queueRead(withStatus(0x02, ht(0x2000, 0x1000)))
        int polled = s.pollInterrupt()
        mock.queueRead(b(0x02))
        s.disableInterrupt(HS40xxFull.SOURCE_RH_HIGH)

        then:
        status == 0x0A
        polled == 0x02
        Arrays.equals(mock.writes().last(), b(0xA6, 0x03, 0x00))
    }

    def "thresholds"() {
        when:
        s.setAlertThresholds(60.0d, 55.0d, null, null, null, null, null, null)

        then:
        writesAre([b(0xA6, 0x0F, 0x66), b(0xA6, 0x10, 0x26), b(0xA6, 0x0D, 0x33), b(0xA6, 0x0E, 0x23)])

        when:
        mock = new MockConnection()
        s = new HS40xxFull(mock)
        s.setAlertThresholds(null, null, null, null, 125.0d, null, -40.0d, null)

        then:
        writesAre([b(0xA6, 0x07, 0xFF), b(0xA6, 0x08, 0x3F), b(0xA6, 0x09, 0x00), b(0xA6, 0x0A, 0x00)])
    }

    def "polarity id and reset"() {
        when:
        mock.queueRead(b(0xB1))
        s.setAlertPolarity(false)

        then:
        Arrays.equals(mock.writes()[1], b(0xA6, 0x02, 0xB0))

        when:
        mock.queueRead(b(0xDE, 0xAD, 0xBE, 0xEF))
        long id = s.readId()

        then:
        id == 0xDEADBEEFL

        when:
        mock = new MockConnection()
        s = new HS40xxFull(mock)
        s.softReset()

        then:
        writesAre([b(0x30), b(0xFE)])
    }

    def "dew point"() {
        when:
        mock.queueRead(ht((int) (0.5d * 16383), (int) ((25.0d + 40.0d) / 165.0d * 16383)))
        double dp = s.dewPoint()
        mock.queueRead(ht(0, 0x1000))
        double dz = s.dewPoint()

        then:
        Math.abs(dp - 13.86d) < 0.1d
        Math.abs(dz + 273.15d) < 1e-9d
    }
}
