package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Mcp4725Spec extends Specification {

    def "full API"() {
        given:
        def connection = new MockConnection()
        def generalCall = new MockConnection()
        def dac = new Mcp4725Full(connection, generalCall)

        when: "setVoltage(0.5) -> code=2048 (0x800), PD=00. Fast Write byte1=0x08, byte2=0x00"
        dac.setVoltage(0.5d)

        then:
        connection.writes().last() == [0x08, 0x00] as byte[]

        when:
        dac.setVoltage(2.0d)

        then:
        connection.writes().last() == [0x0F, (byte) 0xFF] as byte[]

        when:
        dac.setVoltage(-1.0d)

        then:
        connection.writes().last() == [0x00, 0x00] as byte[]

        when: "setRaw(4095) -> byte1=0x0F, byte2=0xFF"
        dac.setRaw(4095)

        then:
        connection.writes().last() == [0x0F, (byte) 0xFF] as byte[]

        when:
        dac.setRaw(5000)

        then:
        connection.writes().last() == [0x0F, (byte) 0xFF] as byte[]

        when: "setVoltageEeprom(0.5) -> code=2048. byte1=0x60, byte2=0x80, byte3=0x00"
        dac.setVoltageEeprom(0.5d)

        then:
        connection.writes().last() == [0x60, (byte) 0x80, 0x00] as byte[]

        when: "setRawEeprom(4095) -> byte2=0xFF, byte3=0xF0"
        dac.setRawEeprom(4095)

        then:
        connection.writes().last() == [0x60, (byte) 0xFF, (byte) 0xF0] as byte[]

        when: "read(): plain 5-byte read. rdy_bsy=1, por=1, pd_dac=2, code=0x123, " +
              "eeprom byte4=0x40 (0100_0000) -> PD1:PD0 bits 6:5 = 2, eeprom_code=0xAB"
        connection.queueRead([(byte) 0xC8, 0x12, 0x30, 0x40, (byte) 0xAB] as byte[])
        def r = dac.read()

        then:
        r.code == 0x123
        Math.abs(r.voltageFraction - (0x123 / 4095.0d)) < 1e-9
        r.powerDown == 2
        r.eepromCode == 0xAB
        r.eepromPowerDown == 2
        r.eepromReady

        when: "setPowerDown(2): uses the driver's cached lastCode (4095, from " +
              "setRawEeprom above), not a fresh read. byte1=(2<<4)|((4095>>8)&0xF)=0x2F"
        dac.setPowerDown(2)

        then:
        connection.writes().last() == [0x2F, (byte) 0xFF] as byte[]

        when: "wakeUp() / reset(): General Call on the separate generalCall connection"
        dac.wakeUp()

        then:
        generalCall.writes().last() == [0x09] as byte[]

        when:
        dac.reset()

        then:
        generalCall.writes().last() == [0x06] as byte[]

        when: "isEepromReady(): RDY/BSY bit, plain 1-byte read"
        connection.queueRead([(byte) 0x80] as byte[])

        then:
        dac.isEepromReady()

        when:
        connection.queueRead([0x00] as byte[])

        then:
        !dac.isEepromReady()
    }
}
