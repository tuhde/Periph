package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Mcp4728Spec extends Specification {

    def "full API"() {
        given:
        def connection = new MockConnection()
        def generalCall = new MockConnection()
        def dac = new Mcp4728Full(connection, generalCall)

        when: "setVoltage(1, 0.5) -> code=2048 (0x800). Multi-Write byte1=0x42, byte2=0x08, byte3=0x00"
        dac.setVoltage(1, 0.5d)

        then:
        connection.writes().last() == [0x42, 0x08, 0x00] as byte[]

        when:
        dac.setVoltage(1, 2.0d)

        then:
        connection.writes().last() == [0x42, 0x0F, (byte) 0xFF] as byte[]

        when: "setRaw(3, 4095) -> byte1=0x46, byte2=0x0F, byte3=0xFF"
        dac.setRaw(3, 4095)

        then:
        connection.writes().last() == [0x46, 0x0F, (byte) 0xFF] as byte[]

        when:
        dac.setRaw(9, 9000)

        then:
        connection.writes().last() == [0x46, 0x0F, (byte) 0xFF] as byte[]

        when: "setAll([0.0, 1.0, 0.5, 0.25]) -> Fast Write, 8 bytes"
        dac.setAll([0.0d, 1.0d, 0.5d, 0.25d] as double[])

        then:
        connection.writes().last() == [0x00, 0x00, 0x0F, (byte) 0xFF, 0x08, 0x00, 0x04, 0x00] as byte[]

        when:
        dac.setAll([0.0d, 1.0d, 0.5d] as double[])

        then:
        thrown(IllegalArgumentException)

        when: "setVoltageEeprom(2, 0.5, vref=1, gain=2) -> code=2048. byte1=0x5C, byte2=0x98, byte3=0x00"
        dac.setVoltageEeprom(2, 0.5d, 1, 2)

        then:
        connection.writes().last() == [0x5C, (byte) 0x98, 0x00] as byte[]

        when: "setRawEeprom(0, 4095, vref=0, gain=1) -> byte1=0x58, byte2=0x0F, byte3=0xFF"
        dac.setRawEeprom(0, 4095, 0, 1)

        then:
        connection.writes().last() == [0x58, 0x0F, (byte) 0xFF] as byte[]

        when: "setAllEeprom: fractions=[0.0,1.0,0.5,0.25], vrefs=[0,1,0,1], gains=[1,2,1,2]"
        dac.setAllEeprom([0.0d, 1.0d, 0.5d, 0.25d] as double[], [0, 1, 0, 1] as int[], [1, 2, 1, 2] as int[])

        then:
        connection.writes().last() == [0x50, 0x00, 0x00, (byte) 0x9F, (byte) 0xFF, 0x08, 0x00, (byte) 0x94, 0x00] as byte[]

        when:
        dac.setAllEeprom([0.0d, 1.0d] as double[], [0, 1] as int[], [1, 2] as int[])

        then:
        thrown(IllegalArgumentException)

        when: "setVref(1, 0, 1, 0) -> byte1 = 0x8A"
        dac.setVref(1, 0, 1, 0)

        then:
        connection.writes().last() == [(byte) 0x8A] as byte[]

        when: "setGain(1, 2, 1, 2) -> byte1 = 0xC5"
        dac.setGain(1, 2, 1, 2)

        then:
        connection.writes().last() == [(byte) 0xC5] as byte[]

        when: "setPowerDown(0, 1, 2, 3) -> byte1=0xA2, byte2=0x58"
        dac.setPowerDown(0, 1, 2, 3)

        then:
        connection.writes().last() == [(byte) 0xA2, 0x58] as byte[]

        when: "read(): 24-byte response, no register-select write"
        byte[] buf = new byte[24]
        buf[0] = (byte) 0x80
        buf[1] = 0x01
        buf[2] = 0x23
        buf[13] = (byte) 0x90
        buf[14] = (byte) 0xAB
        connection.queueRead(buf)
        def result = dac.read()

        then:
        result[0].code == 0x123
        result[0].vref == 0
        result[0].gain == Mcp4728Full.GAIN_X1
        result[0].powerDown == 0
        result[0].eepromCode == 0xAB
        result[0].eepromVref == 1
        result[0].eepromGain == Mcp4728Full.GAIN_X2

        when: "isEepromReady(): RDY/BSY bit, plain 1-byte read"
        connection.queueRead([(byte) 0x80] as byte[])

        then:
        dac.isEepromReady()

        when:
        connection.queueRead([0x00] as byte[])

        then:
        !dac.isEepromReady()

        when: "softwareUpdate()/wakeUp()/reset(): General Call on the separate generalCall connection"
        dac.softwareUpdate()

        then:
        generalCall.writes().last() == [0x08] as byte[]

        when:
        dac.wakeUp()

        then:
        generalCall.writes().last() == [0x09] as byte[]

        when:
        dac.reset()

        then:
        generalCall.writes().last() == [0x06] as byte[]
    }
}
