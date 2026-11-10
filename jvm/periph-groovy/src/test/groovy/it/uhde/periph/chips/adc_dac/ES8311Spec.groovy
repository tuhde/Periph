package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class ES8311Spec extends Specification {

    private static int reg(MockConnection m, int r) {
        Integer v = m.registers().get(r)
        return v == null ? 0 : v.intValue()
    }

    def "init sequence"() {
        given:
        def m = new MockConnection()

        when:
        new ES8311Minimal(m)

        then:
        m.writes()[0] == [0x00, 0x1F] as byte[]
        m.writes()[1] == [0x00, 0x00] as byte[]
        m.writes()[2] == [0x00, (byte) 0x80] as byte[]
        reg(m, 0x01) == 0x3F
        reg(m, 0x02) == 0x00
        reg(m, 0x03) == 0x10
        reg(m, 0x04) == 0x10
        reg(m, 0x06) == 0x03
        reg(m, 0x08) == 0xFF
        reg(m, 0x09) == 0x0C
        reg(m, 0x0A) == 0x0C
        reg(m, 0x0D) == 0x01
        reg(m, 0x0E) == 0x02
        reg(m, 0x13) == 0x10
        reg(m, 0x1C) == 0x6A
        reg(m, 0x37) == 0x08
        reg(m, 0x14) == 0x18
        reg(m, 0x17) == 0xBF
        reg(m, 0x32) == 0x00
        reg(m, 0x16) == 0x04
    }

    def "minimal API"() {
        given:
        def m = new MockConnection()
        def chip = new ES8311Minimal(m)

        when:
        chip.setVolume(0.0d)
        then:
        reg(m, 0x32) == 0xBF

        when:
        chip.setVolume(-95.5d)
        then:
        reg(m, 0x32) == 0x00

        when:
        chip.setVolume(32.0d)
        then:
        reg(m, 0x32) == 0xFF

        when:
        chip.setVolume(100.0d)
        then:
        reg(m, 0x32) == 0xFF

        when:
        chip.setVolume(-10.2d)
        then:
        reg(m, 0x32) == 171

        when:
        m.setRegister(0x31, 0x81)
        chip.setMute(true)
        then:
        reg(m, 0x31) == 0xE1

        when:
        chip.setMute(false)
        then:
        reg(m, 0x31) == 0x81

        when:
        chip.setMicGain(30.0d)
        then:
        reg(m, 0x14) == 0x1A

        when:
        chip.setMicGain(7.4d)
        then:
        reg(m, 0x14) == 0x12

        when:
        chip.setMicGain(99.0d)
        then:
        (reg(m, 0x14) & 0x0F) == 10
    }

    def "clock solver"() {
        when:
        new ES8311Minimal(new MockConnection(), 1000L, 48000L)
        then:
        thrown(IllegalArgumentException)

        when:
        def m = new MockConnection()
        new ES8311Minimal(m, 24576000L, 48000L)
        then:
        reg(m, 0x02) == 0x20

        when:
        def m2 = new MockConnection()
        new ES8311Minimal(m2, 12288000L, 16000L)
        int r02 = reg(m2, 0x02)
        long[] mults = [1L, 2L, 4L, 8L] as long[]
        long ratio = (long) (12288000L * mults[(r02 >> 3) & 3] / (((r02 >> 5) & 7) + 1) / ((reg(m2, 0x05) & 0x0F) + 1) / 16000L)
        then:
        ratio in [256L, 384L, 512L, 768L]
        reg(m2, 0x03) == reg(m2, 0x04)
        reg(m2, 0x03) >= 16 && reg(m2, 0x03) <= 63
    }

    def "full API"() {
        given:
        def m = new MockConnection()
        def chip = new ES8311Full(m)

        when:
        chip.setVolume(-20.0d)
        chip.setMicGain(21.0d)
        then:
        chip.getVolume() == -20.0d
        chip.getMicGain() == 21.0d

        when:
        int n = m.writes().size()
        chip.reset()
        then:
        m.writes().size() == n + 3
        m.writes()[n] == [0, 0x1F] as byte[]
        m.writes()[n + 2] == [0, (byte) 0x80] as byte[]

        when:
        chip.powerDown()
        then:
        reg(m, 0x00) == 0x1F
        (reg(m, 0x31) & 0x60) == 0x60
        reg(m, 0x0D) == 0xFC
        (reg(m, 0x0E) & 0x70) == 0x70

        when:
        chip.powerUp()
        then:
        reg(m, 0x00) == 0x80
        reg(m, 0x0D) == 0x01

        when:
        chip.setLowPower(true, false, false, false, false, false, false, true)
        then:
        reg(m, 0x0F) == 0x81

        when:
        chip.setLowPower(false, true, false, true, true, false, true, false)
        then:
        reg(m, 0x0F) == (0x40 | 0x10 | 0x08 | 0x02)

        when:
        chip.configureClock(12288000L, 48000L, true, true)
        then:
        reg(m, 0x01) == 0xFF

        when:
        chip.configureClock(12288000L, 48000L)
        then:
        reg(m, 0x01) == 0x3F

        when:
        chip.configureClock(1000L, 48000L)
        then:
        thrown(IllegalArgumentException)

        when:
        chip.configureSerial("left_justified", 24)
        then:
        reg(m, 0x09) == 0x01
        reg(m, 0x0A) == 0x01

        when:
        chip.configureSerial("dsp_b", 32, false, false, false, "right", (Integer) null, (Integer) null, true)
        then:
        reg(m, 0x09) == (0x80 | 0x20 | (4 << 2) | 3)
        reg(m, 0x0A) == (0x20 | (4 << 2) | 3)

        when:
        chip.configureSerial("dsp_a", 16, false, false, true, "left", (Integer) null, (Integer) null, true)
        then:
        reg(m, 0x0A) == (0x20 | (3 << 2) | 3)
        (reg(m, 0x00) & 0x44) == 0x04

        when:
        chip.setSdpMute(true, false)
        then:
        (reg(m, 0x09) & 0x40) != 0
        (reg(m, 0x0A) & 0x40) == 0

        when:
        chip.configureSerial("i2s", 16)
        then:
        (reg(m, 0x09) & 0x40) != 0

        when:
        chip.configureSerial("i2s", 16, true, false, false, "left", Integer.valueOf(4), Integer.valueOf(256), true)
        then:
        (reg(m, 0x00) & 0x44) == 0x40
        reg(m, 0x06) == 0x03
        (reg(m, 0x07) & 0x0F) == 0x00
        reg(m, 0x08) == 0xFF

        when:
        chip.configureSerial("i2s", 16, true, true, false, "left", Integer.valueOf(32), Integer.valueOf(64), false)
        then:
        reg(m, 0x06) == (0x40 | 0x20 | 24)

        when:
        chip.configureSerial("x", 16)
        then:
        thrown(IllegalArgumentException)

        when:
        chip.configureSerial("i2s", 8)
        then:
        thrown(IllegalArgumentException)

        when:
        chip.setTristate(true, false)
        then:
        (reg(m, 0x07) & 0x30) == 0x20

        when:
        chip.setTristate(false, true)
        then:
        (reg(m, 0x07) & 0x30) == 0x10

        when:
        chip.setInput("digital")
        then:
        (reg(m, 0x14) & 0x70) == 0x40
        (reg(m, 0x0E) & 0x40) != 0

        when:
        chip.setInput("analog")
        then:
        (reg(m, 0x14) & 0x70) == 0x10
        (reg(m, 0x0E) & 0x40) == 0

        when:
        chip.setInput("none")
        then:
        (reg(m, 0x14) & 0x70) == 0x00
        (reg(m, 0x0E) & 0x40) != 0

        when:
        chip.setInput("x")
        then:
        thrown(IllegalArgumentException)

        when:
        chip.configureDmic(true)
        then:
        (reg(m, 0x15) & 1) == 1

        when:
        chip.configureDmic(false)
        then:
        (reg(m, 0x15) & 1) == 0

        when:
        chip.setAdcVolume(-95.5d)
        then:
        reg(m, 0x17) == 0

        when:
        chip.configureAdc(42, 128, false, false, 0x05, 0x0A)
        then:
        (reg(m, 0x16) & 7) == 7
        reg(m, 0x03) == 32
        (reg(m, 0x1C) & 0x3F) == 0x0A
        (reg(m, 0x1B) & 0x1F) == 0x05

        when:
        chip.configureAdc(24, 64, true, true, 0x0C, 0x0C)
        then:
        reg(m, 0x03) == (0x40 | 32)
        (reg(m, 0x1C) & 0x20) != 0

        when:
        chip.configureAdc(24, 8, false, true, 0, 0)
        then:
        thrown(IllegalArgumentException)

        when:
        chip.configureAdc(24, 20, true, true, 0, 0)
        then:
        thrown(IllegalArgumentException)

        when:
        chip.setAdcRamp(7)
        then:
        (reg(m, 0x15) >> 4) == 7

        when:
        chip.setAdcRamp(99)
        then:
        (reg(m, 0x15) >> 4) == 15

        when:
        chip.configureAlc(true, -12.0d, -18.1d, 3, true, 2, -60.0d)
        then:
        reg(m, 0x18) == 0xC3
        reg(m, 0x19) == ((7 << 4) | 3)
        reg(m, 0x1A) == ((2 << 4) | 6)

        when:
        chip.configureAlc(false)
        then:
        reg(m, 0x18) == 0
        reg(m, 0x19) == 0xF0
        reg(m, 0x1A) == 0

        when:
        chip.setAdcEq([1L, 2L, 3L, 4L, 0x3FFFFFFFL] as long[])
        then:
        (reg(m, 0x1C) & 0x40) == 0
        reg(m, 0x20) == 1
        reg(m, 0x2D) == 0x3F
        reg(m, 0x30) == 0xFF
        m.writes().any { it.length == 21 && it[0] == (byte) 0x1D }

        when:
        chip.bypassAdcEq()
        then:
        (reg(m, 0x1C) & 0x40) == 0x40

        when:
        chip.setAdcEq([1L, 2L] as long[])
        then:
        thrown(IllegalArgumentException)

        when:
        chip.setAdcEq([(1L << 30), 0L, 0L, 0L, 0L] as long[])
        then:
        thrown(IllegalArgumentException)

        when:
        chip.setDacEq([0x100L, 0x200L, 0x300L] as long[])
        then:
        (reg(m, 0x37) & 0x08) == 0
        reg(m, 0x3A) == 1
        reg(m, 0x3E) == 2
        reg(m, 0x42) == 3

        when:
        chip.bypassDacEq()
        then:
        (reg(m, 0x37) & 0x08) == 0x08

        when:
        chip.setDacRamp(8)
        then:
        (reg(m, 0x37) >> 4) == 8
        (reg(m, 0x37) & 0x08) != 0

        when:
        chip.configureDrc(true, -12.0d, -24.1d, 5)
        then:
        reg(m, 0x34) == 0x85
        reg(m, 0x35) == ((7 << 4) | 1)

        when:
        chip.setDacMuteOptions(true, false, true)
        then:
        (reg(m, 0x31) & 0xE0) == 0xC0

        when:
        chip.setDacMuteOptions(true, true, false)
        then:
        (reg(m, 0x31) & 0xE0) == 0x60

        when:
        chip.setLoopback(true)
        then:
        (reg(m, 0x44) & 0x80) != 0

        when:
        chip.setAdcdatMode("dac_adc")
        then:
        reg(m, 0x44) == 0xC0

        when:
        chip.setAdcdatMode("dac_dac")
        then:
        ((reg(m, 0x44) >> 4) & 7) == 6

        when:
        chip.setLoopback(false)
        then:
        reg(m, 0x44) == 0x60

        when:
        chip.setAdcdatMode("x")
        then:
        thrown(IllegalArgumentException)

        when:
        chip.writeRegister(0x40, 0x34)
        then:
        reg(m, 0x40) == 0x34
        chip.readRegister(0x40) == 0x34
    }
}
