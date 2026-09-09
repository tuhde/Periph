package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Pcf8591Spec extends Specification {

    def "full API"() {
        given:
        def connection = new MockConnection()
        def adc = new Pcf8591Full(connection)

        when: "readChannel(2): writes control byte CHN=2, reads 2 bytes; byte0 stale, byte1 fresh"
        connection.queueRead([0x11, 0x7F] as byte[])
        def v1 = adc.readChannel(2)

        then:
        v1 == 0x7F
        connection.writes().last() == [0x02] as byte[]

        when: "readChannel clamps an out-of-range channel to 0"
        connection.queueRead([0x00, 0x55] as byte[])
        def v2 = adc.readChannel(9)

        then:
        v2 == 0x55
        connection.writes().last() == [0x00] as byte[]

        when: "readAll(): writes control with AI=1 (0x04), reads 5 bytes, discards stale byte"
        connection.queueRead([0x00, 0x10, 0x20, 0x30, 0x40] as byte[])
        def all = adc.readAll()

        then:
        all == [0x10, 0x20, 0x30, 0x40] as int[]
        connection.writes().last() == [0x04] as byte[]

        when: "configure(3, true, true) -> control 0x74"
        adc.configure(3, true, true)

        then:
        connection.writes().last() == [0x74] as byte[]

        when: "readChannelVoltage(0, 3.3, 0.0): raw=128"
        connection.queueRead([0x00, (byte) 128] as byte[])
        def cv = adc.readChannelVoltage(0, 3.3d, 0.0d)

        then:
        Math.abs(cv - (128 * 3.3d / 256.0d)) < 1e-9

        when: "readAllVoltage(3.3, 0.0): raws [0, 64, 128, 255]"
        connection.queueRead([0x00, 0, 64, (byte) 128, (byte) 255] as byte[])
        def voltages = adc.readAllVoltage(3.3d, 0.0d)

        then:
        [0, 64, 128, 255].eachWithIndex { raw, i ->
            assert Math.abs(voltages[i] - (raw * 3.3d / 256.0d)) < 1e-9
        }

        when: "readDifferential(1): raw byte 200 -> signed two's complement = -56"
        connection.queueRead([0x00, (byte) 200] as byte[])
        def d1 = adc.readDifferential(1)

        then:
        d1 == -56

        when: "raw byte 100 (< 128) stays positive"
        connection.queueRead([0x00, 100] as byte[])
        def d2 = adc.readDifferential(1)

        then:
        d2 == 100

        when: "setDac(200): sets AOE=1, AI=0, writes [ctrl, value]"
        adc.setDac(200)

        then:
        connection.writes().last()[1] == (byte) 200
        (connection.writes().last()[0] & 0x40) != 0
        (connection.writes().last()[0] & 0x04) == 0

        when: "setDacVoltage(0.5) -> value = round(0.5*255) = 128"
        adc.setDacVoltage(0.5d)

        then:
        connection.writes().last()[1] == (byte) 128

        when: "disableDac(): clears AOE bit"
        adc.disableDac()

        then:
        (connection.writes().last()[0] & 0x40) == 0
    }
}
