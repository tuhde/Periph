package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class HDC1008Spec extends Specification {

    MockConnection mock
    HDC1008Full s

    static byte[] b(int... v) {
        byte[] out = new byte[v.length]
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i]
        return out
    }

    boolean lastIs(int... expected) {
        return Arrays.equals(b(expected), mock.writes().last())
    }

    def setup() {
        mock = new MockConnection()
        s = new HDC1008Full(mock)
    }

    def "constructor does no bus access and init writes the default"() {
        expect:
        mock.writes().isEmpty()
        when:
        s.init()
        then:
        lastIs(0x02, 0x10, 0x00)
        new HDC1008Minimal(new MockConnection()) != null
    }

    def "read triggers pointer 0 and converts a sequence result"() {
        given:
        mock.queueRead(b(0x66, 0x66, 0x80, 0x00))
        when:
        double[] v = s.read()
        then:
        mock.writes().size() == 1
        lastIs(0x00)
        Math.abs(v[0] - (0x6666 / 65536.0d * 165.0d - 40.0d)) < 1e-3
        Math.abs(v[1] - 50.0d) < 1e-3
    }

    def "read polls while NACKed and times out"() {
        when:
        mock.failNextReads(3)
        mock.queueRead(b(0x66, 0x66, 0x80, 0x00))
        then:
        Math.abs(s.read()[1] - 50.0d) < 1e-3
        when:
        setup()
        mock.failNextReads(1000)
        s.read()
        then:
        thrown(IOException)
    }

    def "single measurements switch the mode"() {
        when:
        mock.queueRead(b(0x80, 0x00))
        double t = s.readTemperature()
        then:
        Math.abs(t - 42.5d) < 1e-3
        mock.writes().size() == 2
        Arrays.equals(mock.writes()[0], b(0x02, 0x00, 0x00))
        when:
        mock.queueRead(b(0x80, 0x00))
        double h = s.readHumidity()
        then:
        Math.abs(h - 50.0d) < 1e-3
        lastIs(0x01)
    }

    def "read raw"() {
        given:
        mock.queueRead(b(0x12, 0x34, 0x56, 0x78))
        expect:
        s.readRaw() == [0x1234, 0x5678] as int[]
    }

    def "resolution and conversion time"() {
        when:
        s.setResolution(11, 8)
        then:
        lastIs(0x02, 0x16, 0x00)
        Math.abs(s.conversionTime() - (0.00365d + 0.0025d)) < 1e-9
        when:
        s.setResolution(14, 14)
        then:
        lastIs(0x02, 0x10, 0x00)
        Math.abs(s.conversionTime() - 0.01285d) < 1e-9
        when:
        s.setResolution(8, 14)
        then:
        thrown(IllegalArgumentException)
        when:
        s.setResolution(14, 12)
        then:
        thrown(IllegalArgumentException)
    }

    def "heater battery and config"() {
        when:
        s.setHeater(true)
        then:
        lastIs(0x02, 0x30, 0x00)
        when:
        s.setHeater(false)
        then:
        lastIs(0x02, 0x10, 0x00)
        when:
        mock.queueRead(b(0x30, 0x00))
        then:
        s.isHeaterOn()
        when:
        mock.queueRead(b(0x18, 0x00))
        then:
        s.batteryLow()
        when:
        mock.queueRead(b(0x10, 0x00))
        then:
        !s.batteryLow()
        when:
        mock.queueRead(b(0x10, 0x00))
        then:
        s.readConfig() == 0x1000
    }

    def "software reset rewrites the config"() {
        when:
        s.setHeater(true)
        s.softwareReset()
        def w = mock.writes()
        then:
        Arrays.equals(w[w.size() - 2], b(0x02, 0x80, 0x00))
        Arrays.equals(w[w.size() - 1], b(0x02, 0x30, 0x00))
    }

    def "serial id"() {
        given:
        mock.queueRead(b(0x00, 0x12))
        mock.queueRead(b(0x34, 0x56))
        mock.queueRead(b(0x80, 0x00))
        expect:
        s.serialId() == ((0x12L << 24) | (0x3456L << 8) | 0x100L)
    }

    def "poll interrupt"() {
        when:
        mock.failNextReads(1)
        then:
        !s.pollInterrupt()
        when:
        mock.queueRead(b(0x80, 0x00, 0x80, 0x00))
        then:
        s.pollInterrupt()
    }
}
