package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import it.uhde.periph.connection.OutputPin
import spock.lang.Specification

class CS43L22Spec extends Specification {

    static class Pin implements OutputPin {
        List<Boolean> levels = []
        @Override void set(boolean high) { levels << high }
        @Override void close() { }
    }

    private static int reg(MockConnection m, int r) {
        Integer v = m.registers().get(r)
        return v == null ? 0 : v
    }

    private static MockConnection mock() {
        MockConnection m = new MockConnection()
        m.setRegister(0x01, 0xE1)
        m.setRegister(0x32, 0x3B)
        return m
    }

    private static CS43L22Full chip(MockConnection m) {
        def c = new CS43L22Full(m)
        c.init()
        return c
    }

    def "init sequence"() {
        given:
        def m = mock()
        def pin = new Pin()
        def c = new CS43L22Full(m, pin)

        expect:
        m.writes().size() == 0

        when:
        c.init()
        def req = m.writes().findAll { byte[] w -> w.length == 2 && (w[0] == 0x00 || w[0] == 0x47 || w[0] == 0x32) }
                .collect { byte[] w -> (w[0] & 0xFF) + ':' + (w[1] & 0xFF) }

        then:
        pin.levels == [false, true]
        m.writes()[1] == [0x02, 0x01] as byte[]
        reg(m, 0x04) == 0xAF
        reg(m, 0x05) == 0xA0
        reg(m, 0x06) == 0x04
        reg(m, 0x0D) == 0x60
        reg(m, 0x02) == 0x01
        req == ['0:153', '71:128', '50:187', '50:59', '0:0']

        when:
        new CS43L22Minimal(new MockConnection()).init()

        then:
        thrown(IOException)
    }

    def "start stop and volume"() {
        given:
        def m = mock()
        def c = chip(m)

        when:
        c.start()

        then:
        reg(m, 0x02) == 0x9E

        when:
        m.setRegister(0x0E, 0x03)
        m.setRegister(0x0A, 0x0F)
        c.stop()

        then:
        reg(m, 0x02) == 0x9F
        (reg(m, 0x0F) & 0xF0) == 0xF0
        (reg(m, 0x0E) & 3) == 0
        (reg(m, 0x0A) & 0x0F) == 0

        when:
        c.setVolume(12d)

        then:
        reg(m, 0x20) == 0x18

        when:
        c.setVolume(-102d)

        then:
        reg(m, 0x20) == 0x34

        when:
        c.setVolume(-500d)

        then:
        reg(m, 0x21) == 0x34

        when:
        c.setVolume(-0.5d)

        then:
        reg(m, 0x20) == 0xFF
        c.getVolume() == -0.5d

        when:
        c.setVolumeChannels(-10d, 3d)

        then:
        reg(m, 0x20) == 0xEC
        reg(m, 0x21) == 0x06
    }

    def "channel volumes and mutes"() {
        given:
        def m = mock()
        def c = chip(m)

        when:
        c.setHeadphoneVolume(-20d)

        then:
        reg(m, 0x22) == 0xD8
        reg(m, 0x23) == 0xD8

        when:
        c.setHeadphoneVolume(-96d, -100d)

        then:
        reg(m, 0x22) == 0x40
        reg(m, 0x23) == 0x01

        when:
        c.setSpeakerVolume(5d, -1d)

        then:
        reg(m, 0x24) == 0x00
        reg(m, 0x25) == 0xFE

        when:
        c.setPcmVolume(-51.5d, 12d)

        then:
        reg(m, 0x1A) == 0x19
        reg(m, 0x1B) == 0x18

        when:
        c.mutePcm(true, false)

        then:
        reg(m, 0x1A) == 0x99

        when:
        c.setPcmVolume(0d)

        then:
        reg(m, 0x1A) == 0x80

        when:
        c.setPassthroughVolume(-60d, 12d)

        then:
        reg(m, 0x14) == 0x88
        reg(m, 0x15) == 0x18

        when:
        m.setRegister(0x0F, 0)
        c.mute(true)

        then:
        reg(m, 0x0F) == 0xC0

        when:
        c.mute(false)

        then:
        reg(m, 0x0F) == 0x00

        when:
        c.muteOutputs(null, true)

        then:
        reg(m, 0x0F) == 0x30

        when:
        c.muteOutputs(true, false)

        then:
        reg(m, 0x0F) == 0xC0

        when:
        c.setSpeakerMode(true, false, true)

        then:
        (reg(m, 0x0F) & 7) == 3
    }

    def "outputs gain clocking interface"() {
        given:
        def m = mock()
        def c = chip(m)

        when:
        c.setOutputs(CS43L22Full.OUT_ON, CS43L22Full.OUT_OFF)

        then:
        reg(m, 0x04) == 0xAF

        when:
        c.setOutputs(0, 1)

        then:
        reg(m, 0x04) == 0x05

        when:
        c.setOutputs(4, 0)

        then:
        thrown(IllegalArgumentException)

        when:
        c.setHeadphoneGain(CS43L22Full.GAIN_1_000)

        then:
        reg(m, 0x0D) == 0xC0
        c.headphoneGain() == 1.0d

        when:
        c.setHeadphoneGain(8)

        then:
        thrown(IllegalArgumentException)

        when:
        c.setClocking(new CS43L22Full.Clocking(true, 1, false, false, 0, false))

        then:
        reg(m, 0x05) == 0xA0

        when:
        c.setClocking(new CS43L22Full.Clocking(false, 3, true, true, 3, true))

        then:
        reg(m, 0x05) == 0x7F

        when:
        c.setClocking(new CS43L22Full.Clocking(true, 4, false, false, 0, false))

        then:
        thrown(IllegalArgumentException)

        when:
        c.setInterface()

        then:
        reg(m, 0x06) == 0x04
        reg(m, 0x07) == 0x00

        when:
        c.setInterface(true, CS43L22Full.FMT_RJ, 2, false, true, true, true)

        then:
        reg(m, 0x06) == 0xCA
        reg(m, 0x07) == 0x48

        when:
        c.setInterface(false, CS43L22Full.FMT_LJ, 1, true, false, false, false)

        then:
        reg(m, 0x06) == 0x11

        when:
        c.setInterface(false, CS43L22Full.FMT_I2S, 0, true, false, false, false)

        then:
        thrown(IllegalArgumentException)
    }

    def "clocking table"() {
        expect:
        !CS43L22Full.clockingFor(12288000L, 48000d).auto
        CS43L22Full.clockingFor(12288000L, 48000d).speed == 1
        CS43L22Full.clockingFor(12288000L, 8000d).speed == 3
        CS43L22Full.clockingFor(12288000L, 8000d).group32k
        CS43L22Full.clockingFor(11289600L, 44100d).ratio == 0
        CS43L22Full.clockingFor(12000000L, 48000d).ratio == 1
        CS43L22Full.clockingFor(24000000L, 44117.647d).ratio == 3
        CS43L22Full.clockingFor(24000000L, 44117.647d).mclkDiv2
        CS43L22Full.clockingFor(27000000L, 16000d).videoClk
        CS43L22Full.clockingFor(27000000L, 16000d).speed == 2
        CS43L22Full.clockingFor(16934400L, 8018.18d).ratio == 2

        when:
        CS43L22Full.clockingFor(12288000L, 44100d)

        then:
        thrown(IllegalArgumentException)
    }

    def "tone beep limiter"() {
        given:
        def m = mock()
        def c = chip(m)

        when:
        c.setTone(12d, -10.5d, 1, 2, true)

        then:
        reg(m, 0x1F) == 0x0F
        (reg(m, 0x1E) & 0x1F) == 0x0D

        when:
        c.setTone(0d, 0d, 0, 0, false)

        then:
        reg(m, 0x1F) == 0x88
        (reg(m, 0x1E) & 1) == 0

        when:
        c.setBeep(0xC, 2, 3, 6d, CS43L22Full.BEEP_SINGLE)

        then:
        reg(m, 0x1C) == 0xC2
        reg(m, 0x1D) == 0x66
        (reg(m, 0x1E) >> 6) == 1

        when:
        c.setBeep(1, 1, 1, 0d, 1)

        then:
        thrown(IllegalStateException)

        when:
        c.stopBeep()
        c.setBeep(1, 1, 0, -56d, 3, false)

        then:
        (reg(m, 0x1D) & 0x1F) == 0x07
        (reg(m, 0x1E) & 0xE0) == 0xE0

        when:
        c.setBeep(1, 1, 0, 0d, CS43L22Full.BEEP_OFF)

        then:
        (reg(m, 0x1E) >> 6) == 0

        when:
        c.setBeep(1, 1, 0, 0d, 2)

        then:
        (reg(m, 0x1D) & 0x1F) == 0x03

        when:
        c.stopBeep()
        c.setBeep(16, 0, 0, 0d, 1)

        then:
        thrown(IllegalArgumentException)

        when:
        c.setLimiter(true, -6, -12, 0x20, 5, false, true, true)

        then:
        reg(m, 0x27) == 0x53
        reg(m, 0x28) == 0xA0
        reg(m, 0x29) == 5

        when:
        c.setLimiter(false, -29, 0)

        then:
        (reg(m, 0x27) >> 5) == 7
        reg(m, 0x28) == 0x7F

        when:
        c.setLimiter(true, 0, 0, 64)

        then:
        thrown(IllegalArgumentException)
    }

    def "dsp passthrough status raw"() {
        given:
        def m = mock()
        def c = chip(m)
        m.setRegister(0x0E, 0)
        m.setRegister(0x0A, 0xA5)

        when:
        c.setSoftRamp(true, true, false)

        then:
        reg(m, 0x0E) == 0x02
        reg(m, 0x0A) == 0xA7

        when:
        c.setZeroCross(true, false, true)

        then:
        reg(m, 0x0E) == 0x03
        reg(m, 0x0A) == 0xA6

        when:
        c.setDeemphasis(true)
        c.freeze(true)

        then:
        (reg(m, 0x0E) & 0x0C) == 0x0C

        when:
        c.freeze(false)

        then:
        (reg(m, 0x0E) & 0x08) == 0

        when:
        c.setPcmSwap(CS43L22Full.SRC_SWAPPED, CS43L22Full.SRC_MIX)
        c.invertPcm(true, false)

        then:
        reg(m, 0x26) == 0xD0
        (reg(m, 0x0D) & 0x0C) == 0x04

        when:
        c.setPassthrough(true, 2, 4)

        then:
        reg(m, 0x08) == 0x82
        reg(m, 0x09) == 0x88
        (reg(m, 0x0E) & 0xC0) == 0xC0

        when:
        c.setPassthrough(true, 0, 1)

        then:
        thrown(IllegalArgumentException)

        when:
        c.mutePassthrough(true, false)
        c.setBatteryCompensation(true, 9, true)
        m.setRegister(0x30, 0x40)

        then:
        (reg(m, 0x0E) & 0x30) == 0x10
        reg(m, 0x2F) == 0xC9
        Math.abs(c.readVpVoltage(2.5d) - 64 * 2.5d / 63.3d) < 1e-9

        when:
        m.setRegister(0x2E, 0x4C)
        def st = c.readStatus()
        m.setRegister(0x31, 0x28)
        def sp = c.readSpeakerStatus()

        then:
        st.clockError && st.pcmAOverflow && st.pcmBOverflow
        !st.dspAOverflow && !st.dspBOverflow
        sp.speakerAShort && sp.spkrHpPin
        !sp.speakerBShort

        when:
        m.setRegister(0x34, 0x5F)
        c.setChargePumpFrequency(7)

        then:
        reg(m, 0x34) == 0x7F

        when:
        c.setChargePumpFrequency(16)

        then:
        thrown(IllegalArgumentException)

        expect:
        c.readId() == [0x1C, 1] as int[]
        c.readRegister(0x01) == 0xE1

        when:
        c.readRegister(0x03)

        then:
        thrown(IllegalArgumentException)

        when:
        c.writeRegister(0x20, 0x10)

        then:
        reg(m, 0x20) == 0x10

        when:
        c.writeRegister(0x2E, 0)

        then:
        thrown(IllegalArgumentException)

        when:
        c.writeRegister(0x33, 0)

        then:
        thrown(IllegalArgumentException)

        when:
        c.reset()

        then:
        thrown(IllegalStateException)
    }

    def "reset reinitializes"() {
        given:
        def m = mock()
        def pin = new Pin()
        def c = new CS43L22Full(m, pin)
        c.init()
        c.start()

        when:
        c.reset()

        then:
        reg(m, 0x02) == 0x01
        pin.levels.size() == 4
    }
}
