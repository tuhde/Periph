package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Pcf8576Spec extends Specification {

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
        def sensor = new Pcf8576Full(connection)

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
        sensor.setDigit7seg(3, Pcf8576Minimal.SEVEN_SEG[7] as int)

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
        sensor.setMode(Pcf8576Full.BACKPLANES_1, Pcf8576Full.BIAS_1_2_FULL)

        then:
        lastWrite(connection) == [0x4D] as byte[] // 0x40|8|4|1

        when:
        sensor.setMode(Pcf8576Full.BACKPLANES_2, Pcf8576Full.BIAS_1_3_FULL)

        then:
        lastWrite(connection) == [0x4A] as byte[] // 0x40|8|0|2

        when:
        sensor.setMode(Pcf8576Full.BACKPLANES_3, Pcf8576Full.BIAS_1_3_FULL)

        then:
        lastWrite(connection) == [0x4B] as byte[] // 0x40|8|0|3

        when:
        sensor.setMode(Pcf8576Full.BACKPLANES_4, Pcf8576Full.BIAS_1_3_FULL)

        then:
        lastWrite(connection) == [0x48] as byte[] // 0x40|8|0|0

        when:
        sensor.setBlink(Pcf8576Full.BLINK_1_HZ, false)

        then:
        lastWrite(connection) == [0x72] as byte[] // 0x70|0|2

        when:
        sensor.setBlink(Pcf8576Full.BLINK_2_HZ, true)

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
        // Regression test for a bug where Pcf8576Minimal's `backplanes`
        // field defaulted to MODE_1_4 (0x00, a drive-mode bit pattern)
        // instead of the backplane count 4 - harmless only by coincidence,
        // since Pcf8576Full.modeCode()'s default arm also happens to
        // return MODE_1_4.
        given:
        def connection = new MockConnection()

        when:
        def sensor = new Pcf8576Minimal(connection)

        then:
        sensor.backplanes == 4
    }
}
