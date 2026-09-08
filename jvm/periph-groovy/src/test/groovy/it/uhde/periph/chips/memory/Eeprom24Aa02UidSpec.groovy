package it.uhde.periph.chips.memory

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Eeprom24Aa02UidSpec extends Specification {

    def "full API"() {
        given:
        def connection = new MockConnection()
        // UID (0xFC-0xFF), MSB first.
        connection.setRegister(Eeprom24Aa02UidMinimal.ADDR_UID_BASE, 0xAA, 0xBB, 0xCC, 0xDD)

        when:
        def eeprom = new Eeprom24Aa02UidFull(connection)

        then:
        eeprom.readUid() == [0xAA, 0xBB, 0xCC, 0xDD] as byte[]

        when:
        connection.setRegister(0x10, 0x42)

        then:
        eeprom.readByte(0x10) == 0x42

        when:
        eeprom.writeByte(0x10, 0x99)

        then:
        connection.registers().get(0x10) == 0x99
        connection.writes().last() == [0x10, (byte) 0x99] as byte[]

        when: "sequential read (0x05-0x08)"
        connection.setRegister(0x05, 1, 2, 3, 4)

        then:
        eeprom.read(0x05, 4) == [1, 2, 3, 4] as byte[]

        when:
        eeprom.writePage(0x08, [10, 20, 30] as byte[])

        then:
        connection.registers().get(0x08) == 10
        connection.registers().get(0x09) == 20
        connection.registers().get(0x0A) == 30

        when: "write() spans a page boundary: page 0 is 0x00-0x07, page 1 is 0x08-0x0F"
        // Starting at 0x05 with 10 bytes -> [0x05,0x06,0x07] (3 bytes, page 0)
        // then [0x08..0x0E] (7 bytes, page 1). writePage() issues exactly one
        // write per call (no ack-poll traffic in Groovy), so the two
        // page-chunk writes are the last two writes.
        byte[] data10 = (0..9).collect { (byte) (100 + it) } as byte[]
        eeprom.write(0x05, data10)
        def writes = connection.writes()

        then:
        writes[-2] == [0x05, 100, 101, 102] as byte[]
        writes[-1] == [0x08, 103, 104, 105, 106, 107, 108, 109] as byte[]
        connection.registers().get(0x05) == 100
        connection.registers().get(0x06) == 101
        connection.registers().get(0x07) == 102
        connection.registers().get(0x08) == 103
        connection.registers().get(0x0E) == 109

        when:
        connection.setRegister(Eeprom24Aa02UidMinimal.ADDR_MFR_CODE, 0x29)

        then:
        eeprom.readManufacturerCode() == 0x29

        when:
        connection.setRegister(Eeprom24Aa02UidMinimal.ADDR_DEV_CODE, 0x41)

        then:
        eeprom.readDeviceCode() == 0x41
    }
}
