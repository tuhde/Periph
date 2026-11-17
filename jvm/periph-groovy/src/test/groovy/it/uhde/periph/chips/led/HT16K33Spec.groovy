package it.uhde.periph.chips.led

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class HT16K33Spec extends Specification {

    private static byte[] zeros21() {
        new byte[21]
    }

    private static byte[] lastWrite(MockConnection connection) {
        def writes = connection.writes()
        writes[writes.size() - 1]
    }

    def "full API"() {
        given:
        def connection = new MockConnection()

        when:
        def sensor = new HT16K33Full(connection)

        then: "init: mode-set (E=1, bias=1/3, mode=1:4 -> 0x48), then load-ptr(0) + 20 zero bytes"
        connection.writes()[0] == [0x48] as byte[]
        connection.writes()[1] == zeros21()

        when:
        sensor.clear()
        def clearWrites = connection.writes()

        then:
        clearWrites[clearWrites.size() - 2] == [0x48] as byte[]
        clearWrites[clearWrites.size() - 1] == zeros21()

        when:
        sensor.writeRaw(5, [0xAB, 0xCD] as byte[])

        then:
        lastWrite(connection) == [0x05, 0xAB, 0xCD] as byte[]

        when:
        int nBefore = connection.writes().size()
        sensor.writeRaw(3, new byte[0])

        then:
        connection.writes().size() == nBefore

        when: "digit '7' -> 0xE0, at RAM address 3*2=6"
        sensor.setDigit7seg(3, HT16K33Minimal.SEVEN_SEG[7] as int)

        then:
        lastWrite(connection) == [0x06, 0xE0] as byte[]

        when:
        sensor.disable()

        then:
        lastWrite(connection) == [0x40] as byte[]

        when:
        sensor.enable()

        then:
        lastWrite(connection) == [0x48] as byte[]

        when: "setMode(): mode-set byte = 0x40 | E(0x08) | bias | mode"
        sensor.setMode(HT16K33Full.BACKPLANES_1, HT16K33Full.BIAS_1_2_FULL)

        then:
        lastWrite(connection) == [0x4D] as byte[] // 0x40|8|4|1

        when:
        sensor.setMode(HT16K33Full.BACKPLANES_2, HT16K33Full.BIAS_1_3_FULL)

        then:
        lastWrite(connection) == [0x4A] as byte[] // 0x40|8|0|2

        when:
        sensor.setMode(HT16K33Full.BACKPLANES_3, HT16K33Full.BIAS_1_3_FULL)

        then:
        lastWrite(connection) == [0x4B] as byte[] // 0x40|8|0|3

        when:
        sensor.setMode(HT16K33Full.BACKPLANES_4, HT16K33Full.BIAS_1_3_FULL)

        then:
        lastWrite(connection) == [0x48] as byte[] // 0x40|8|0|0

        when:
        sensor.setBlink(HT16K33Full.BLINK_1_HZ, false)

        then:
        lastWrite(connection) == [0x72] as byte[] // 0x70|0|2

        when:
        sensor.setBlink(HT16K33Full.BLINK_2_HZ, true)

        then:
        lastWrite(connection) == [0x75] as byte[] // 0x70|4|1

        when:
        sensor.setBank(1, 0)

        then:
        lastWrite(connection) == [0x7A] as byte[] // 0x78|(1<<1)|0

        when:
        sensor.deviceSelect(5)

        then:
        lastWrite(connection) == [0x65] as byte[] // 0x60|5
    }

    def "minimal init matches default backplanes"() {
        // Regression test for a bug where HT16K33Minimal's `backplanes`
        // field defaulted to MODE_1_4 (0x00, a drive-mode bit pattern)
        // instead of the backplane count 4 - harmless only by coincidence,
        // since HT16K33Full.modeCode()'s default arm also happens to
        // return MODE_1_4.
        given:
        def connection = new MockConnection()

        when:
        def sensor = new HT16K33Minimal(connection)

        then:
        sensor.backplanes == 4
    }
}
