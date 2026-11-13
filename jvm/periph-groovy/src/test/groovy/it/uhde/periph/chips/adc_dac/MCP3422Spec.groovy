package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.TimeoutException

class MCP3422Spec extends Specification {

    def setupSpec() {
        MCP3422Minimal.realWaits = false
    }

    private static byte[] frame(int raw, int bits, boolean rdy) {
        int n = bits == 18 ? 3 : 2
        byte[] out = new byte[n + 1]
        for (int i = 0; i < n; i++) out[i] = (byte) ((raw >> (8 * (n - 1 - i))) & 0xFF)
        out[n] = (byte) (rdy ? 0x80 : 0x00)
        return out
    }

    private static MockConnection mock(Object... frames) {
        def c = new MockConnection()
        // Groovy spreads a lone byte[] argument into separate Byte varargs; rebuild the single frame
        if (frames.length > 0 && frames[0] instanceof Byte) {
            byte[] one = new byte[frames.length]
            for (int i = 0; i < frames.length; i++) one[i] = (Byte) frames[i]
            c.queueRead(one)
            return c
        }
        for (Object f : frames) c.queueRead((byte[]) f)
        return c
    }

    private static int lastWrite(MockConnection c) {
        return c.writes().last()[0] & 0xFF
    }

    def "init and config bytes"() {
        given:
        def c = mock(frame(0, 16, false), frame(0, 16, false), frame(0, 16, false), frame(0, 16, false))
        def chip = new MCP3422Full(c)

        expect:
        c.writes().size() == 1
        lastWrite(c) == 0x08

        when:
        chip.readRaw(0)
        then:
        lastWrite(c) == 0x88
        when:
        chip.readRaw(1)
        then:
        lastWrite(c) == 0xA8
        when:
        chip.readRaw(7)
        then:
        lastWrite(c) == 0xA8
        when:
        chip.readRaw(-3)
        then:
        lastWrite(c) == 0x88
    }

    def "decoding"() {
        expect:
        def chip = new MCP3422Full(mock(frame(code, bits, false)))
        chip.setResolution(bits)
        chip.readRaw(0) == code

        where:
        bits | code
        12   | -100
        12   | 2047
        12   | -2048
        14   | -8192
        14   | 8191
        16   | 12345
        16   | -12345
        16   | -32768
        18   | 131071
        18   | -131072
        18   | -1
    }

    def "18 bit ignores the upper six bits of the first byte"() {
        given:
        def chip = new MCP3422Full(mock([0xFC, 0x00, 0x05, 0x00] as byte[]))
        chip.setResolution(18)

        expect:
        chip.readRaw(0) == 5
    }

    def "voltages and derived values"() {
        given:
        def chip = new MCP3422Full(mock(frame(16384, 16, false), frame(-32768, 16, false)))

        expect:
        Math.abs(chip.readVoltage(0) - 16384 * 2 * 2.048d / 65536) < 1e-9

        when:
        chip.setGain(8)
        then:
        Math.abs(chip.readVoltage(0) - (-0.256d)) < 1e-9
        Math.abs(chip.fullScale() - 0.256d) < 1e-9
        Math.abs(chip.lsb() - 62.5e-6d) < 1e-12

        when:
        chip.setResolution(12)
        then:
        Math.abs(chip.lsb() - 1e-3d) < 1e-12

        when:
        chip.setResolution(18)
        then:
        Math.abs(chip.lsb() - 15.625e-6d) < 1e-12
    }

    def "read all and scan"() {
        given:
        def c = mock(frame(100, 16, false), frame(200, 16, false), frame(10, 16, false), frame(20, 16, false), frame(30, 16, false))
        def chip = new MCP3422Full(c)

        when:
        double[] v = chip.readAll()
        then:
        Math.abs(v[0] - 100 * 2 * 2.048d / 65536) < 1e-9
        Math.abs(v[1] - 200 * 2 * 2.048d / 65536) < 1e-9
        (c.writes()[1][0] & 0xFF) == 0x88
        (c.writes()[2][0] & 0xFF) == 0xA8

        when:
        double[] s = chip.readScan([1, 0, 1] as int[])
        then:
        s.length == 3
        (c.writes()[3][0] & 0xFF) == 0xA8
        (c.writes()[4][0] & 0xFF) == 0x88
        (c.writes()[5][0] & 0xFF) == 0xA8
    }

    def "polling and timeout"() {
        given:
        def chip = new MCP3422Full(mock(frame(1, 16, true), frame(1, 16, true), frame(777, 16, false)))

        expect:
        chip.readRaw(0) == 777

        when:
        byte[][] stale = new byte[40][]
        for (int i = 0; i < stale.length; i++) stale[i] = frame(1, 16, true)
        def chip2 = new MCP3422Full(mock((Object[]) stale))
        chip2.readRaw(0)
        then:
        thrown(TimeoutException)
    }

    def "selection"() {
        given:
        def c = mock(frame(0, 18, false))
        def chip = new MCP3422Full(c)

        when:
        chip.setResolution(10)
        then:
        thrown(IllegalArgumentException)
        when:
        chip.setGain(3)
        then:
        thrown(IllegalArgumentException)

        when:
        chip.setResolution(18)
        chip.setGain(8)
        chip.readRaw(0)
        then:
        chip.getResolution() == 18
        chip.getGain() == 8
        lastWrite(c) == 0x8F
    }

    def "continuous mode"() {
        given:
        def c = mock(frame(5, 12, false), frame(300, 12, false), frame(301, 12, false))
        def chip = new MCP3422Full(c)
        chip.setResolution(12)
        chip.startContinuous()

        expect:
        lastWrite(c) == 0x10
        chip.isContinuous()
        chip.readContinuousRaw() == 300
        chip.readContinuousRaw() == 301

        when:
        chip.startContinuous(1)
        then:
        lastWrite(c) == 0x30

        when:
        chip.stopContinuous()
        then:
        lastWrite(c) == 0x20
        !chip.isContinuous()

        when:
        chip.readContinuousRaw()
        then:
        thrown(IllegalStateException)
    }

    def "gain change in continuous mode rewrites config and discards one sample"() {
        given:
        def c2 = mock(frame(1, 12, false), frame(2, 12, false), frame(3, 12, false), frame(4, 12, false))
        def chip2 = new MCP3422Full(c2)
        chip2.setResolution(12)
        chip2.startContinuous()
        chip2.readContinuousRaw()
        int n = c2.writes().size()

        when:
        chip2.setGain(2)
        then:
        c2.writes().size() == n + 1
        lastWrite(c2) == 0x11
        chip2.readContinuousRaw() == 4
    }

    def "continuous voltage"() {
        given:
        def chip3 = new MCP3422Full(mock(frame(5, 16, false), frame(16384, 16, false)))
        chip3.startContinuous()

        expect:
        Math.abs(chip3.readContinuous() - 16384 * 2 * 2.048d / 65536) < 1e-9
    }

    def "config and general calls"() {
        given:
        def chip = new MCP3422Full(mock(frame(0, 16, true), frame(0, 16, false)))

        expect:
        chip.readConfig() == 0x80
        chip.isReady()

        when:
        def c = mock(frame(321, 16, false))
        def chip2 = new MCP3422Full(c)
        chip2.setResolution(18)
        chip2.setGain(8)
        chip2.generalCallReset()
        then:
        c.writes().last() == [0x00, 0x06] as byte[]
        chip2.getResolution() == 12
        chip2.getGain() == 1
        chip2.isContinuous()

        when:
        chip2.stopContinuous()
        chip2.setResolution(16)
        chip2.generalCallConvert()
        then:
        c.writes().last() == [0x00, 0x08] as byte[]
        chip2.readPendingRaw() == 321
    }
}
