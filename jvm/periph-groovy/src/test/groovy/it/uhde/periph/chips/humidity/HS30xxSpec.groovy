package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class HS30xxSpec extends Specification {

    MockConnection mock
    HS30xxFull s

    static byte[] frame(int rh, int t, int status) {
        return [(status << 6) | (rh >> 8), rh & 0xFF, t >> 6, (t & 0x3F) << 2] as byte[]
    }

    static byte[] b(int... v) {
        byte[] out = new byte[v.length]
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i]
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
        s = new HS30xxFull(mock)
    }

    def "constructor does no bus access"() {
        expect:
        mock.writes().isEmpty()
    }

    def "aliases extend the drivers"() {
        expect:
        new HS3001Full(mock) instanceof HS30xxFull
        new HS3002Full(mock) instanceof HS30xxMinimal
        new HS3003Minimal(mock) instanceof HS30xxMinimal
        new HS3001Minimal(mock) instanceof HS30xxMinimal
    }

    def "conversions"() {
        when:
        mock.queueRead(frame(0x2000, 0x1000, 0))
        double[] m = s.read()

        then:
        writesAre([new byte[0]])
        Math.abs(m[1] - 50.0d) < 0.01d
        Math.abs(m[0] - 1.25d) < 0.01d
        Math.abs(m[1] - 8192 / 16383.0d * 100.0d) < 1e-9d

        when:
        mock.queueRead(frame(0x3FFF, 0x3FFF, 0))
        m = s.read()

        then:
        Math.abs(m[1] - 100.0d) < 1e-6d
        Math.abs(m[0] - 125.0d) < 1e-6d

        when:
        mock.queueRead(frame(0, 0, 0))
        m = s.read()

        then:
        Math.abs(m[1]) < 1e-9d
        Math.abs(m[0] + 40.0d) < 1e-9d
    }

    def "masks undetermined bits"() {
        when:
        mock.queueRead(b(0x20, 0x00, 0x10, 0x03))

        then:
        Math.abs(s.read()[0] - (0x400 / 16383.0d * 165.0d - 40.0d)) < 1e-9d
    }

    def "stale retries once then fails"() {
        when:
        mock.queueRead(frame(0x2000, 0x1000, 1))
        mock.queueRead(frame(0x2000, 0x1000, 0))

        then:
        Math.abs(s.read()[1] - 50.0d) < 0.01d
        mock.writes().size() == 1

        when:
        mock.queueRead(frame(0, 0, 1))
        mock.queueRead(frame(0, 0, 1))
        s.read()

        then:
        thrown(IOException)
    }

    def "bus error on request"() {
        when:
        mock.failNextWrites(1, 0)
        s.read()

        then:
        thrown(IOException)
    }

    def "single values and raw"() {
        when:
        mock.queueRead(frame(0x2000, 0x1000, 0))

        then:
        Math.abs(s.readTemperature() - 1.25d) < 0.01d

        when:
        mock.queueRead(b(0x20, 0x00))

        then:
        Math.abs(s.readHumidity() - 50.0d) < 0.01d

        when:
        mock.queueRead(frame(0x1234, 0x0ABC, 0))

        then:
        Arrays.equals(s.readRaw(), [0x1234, 0x0ABC, 0] as int[])

        when:
        mock.queueRead(frame(0x1234, 0x0ABC, 1))

        then:
        s.readRaw()[2] == 1

        when:
        s.setMeasurementWait(0L)

        then:
        thrown(IllegalArgumentException)

        when:
        s.setMeasurementWait(5L)
        mock.queueRead(frame(0x2000, 0x1000, 0))

        then:
        Math.abs(s.readHumidity() - 50.0d) < 0.01d
    }

    def "programming commands and NVM read"() {
        when:
        s.enterProgrammingMode()
        s.exitProgrammingMode()

        then:
        writesAre([b(0xA0, 0, 0), b(0x80, 0, 0)])

        when:
        setup()
        mock.queueRead(b(0x81, 0x12, 0x34))

        then:
        s.readNvm(0x1E) == 0x1234
        writesAre([b(0x1E, 0, 0)])

        when:
        mock.queueRead(b(0x80, 0x12, 0x34))
        s.readNvm(0x1E)

        then:
        thrown(IOException)
    }

    def "NVM methods need a power cycle"() {
        when:
        s.getResolution((Runnable) null)

        then:
        thrown(IllegalStateException)

        when:
        s.setResolution(12, 12, (Runnable) null)

        then:
        thrown(IllegalStateException)

        when:
        s.readId((Runnable) null)

        then:
        thrown(IllegalStateException)
        mock.writes().isEmpty()
    }

    def "getResolution"() {
        given:
        int[] cycles = [0] as int[]
        mock.queueRead(b(0x81, 0x0C, 0x00))
        mock.queueRead(b(0x81, 0x08, 0x05))

        expect:
        Arrays.equals(s.getResolution({ -> cycles[0]++ } as Runnable), [14, 12] as int[])
        cycles[0] == 1
        writesAre([b(0xA0, 0, 0), b(0x06, 0, 0), b(0x11, 0, 0), b(0x80, 0, 0)])
    }

    def "setResolution preserves other bits"() {
        given:
        mock.queueRead(b(0x81, 0x0C, 0x07))
        mock.queueRead(b(0x81, 0x0C, 0x21))

        when:
        s.setResolution(8, 12, { -> } as Runnable)

        then:
        writesAre([b(0xA0, 0, 0), b(0x06, 0, 0), b(0x46, 0x00, 0x07),
                   b(0x11, 0, 0), b(0x51, 0x08, 0x21), b(0x80, 0, 0)])
    }

    def "setResolution one channel and invalid"() {
        given:
        mock.queueRead(b(0x81, 0x00, 0x00))

        when:
        s.setResolution(null, 10, { -> } as Runnable)

        then:
        writesAre([b(0xA0, 0, 0), b(0x11, 0, 0), b(0x51, 0x04, 0x00), b(0x80, 0, 0)])

        when:
        setup()
        s.setResolution(9, null, { -> } as Runnable)

        then:
        thrown(IllegalArgumentException)
        mock.writes().isEmpty()
    }

    def "failed NVM read still leaves programming mode"() {
        given:
        mock.queueRead(b(0x00, 0, 0))

        when:
        s.getResolution({ -> } as Runnable)

        then:
        thrown(IOException)
        Arrays.equals(mock.writes().last(), b(0x80, 0, 0))
    }

    def "readId"() {
        given:
        mock.queueRead(b(0x81, 0xDE, 0xAD))
        mock.queueRead(b(0x81, 0xBE, 0xEF))

        expect:
        s.readId({ -> } as Runnable) == 0xDEADBEEFL
    }

    def "dew point"() {
        when:
        mock.queueRead(frame(8191, 6453, 0))

        then:
        Math.abs(s.dewPoint() - 13.86d) < 0.1d

        when:
        mock.queueRead(frame(0, 0x1000, 0))

        then:
        Math.abs(s.dewPoint() + 273.15d) < 1e-9d
    }
}
