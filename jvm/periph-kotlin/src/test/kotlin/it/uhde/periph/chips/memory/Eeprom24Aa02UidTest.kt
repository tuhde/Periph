package it.uhde.periph.chips.memory

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Eeprom24Aa02UidTest {

    @Test
    fun fullApi() {
        val connection = MockConnection()
        // UID (0xFC-0xFF), MSB first.
        connection.setRegister(Eeprom24Aa02UidMinimal.ADDR_UID_BASE, 0xAA, 0xBB, 0xCC, 0xDD)

        val eeprom = Eeprom24Aa02UidFull(connection)

        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()), eeprom.readUid())

        connection.setRegister(0x10, 0x42)
        assertEquals(0x42, eeprom.readByte(0x10))

        eeprom.writeByte(0x10, 0x99)
        assertEquals(0x99, connection.registers()[0x10])
        val lastWrite = connection.writes().last()
        assertArrayEquals(byteArrayOf(0x10, 0x99.toByte()), lastWrite)

        // Sequential read (0x05-0x08).
        connection.setRegister(0x05, 1, 2, 3, 4)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), eeprom.read(0x05, 4))

        eeprom.writePage(0x08, byteArrayOf(10, 20, 30))
        assertEquals(10, connection.registers()[0x08])
        assertEquals(20, connection.registers()[0x09])
        assertEquals(30, connection.registers()[0x0A])

        // write() spanning a page boundary: page 0 is 0x00-0x07, page 1 is
        // 0x08-0x0F. Starting at 0x05 with 10 bytes -> [0x05,0x06,0x07] (3
        // bytes, page 0) then [0x08..0x0E] (7 bytes, page 1). writePage()
        // issues exactly one write per call (no ack-poll traffic in
        // Kotlin), so the two page-chunk writes are the last two writes.
        val data10 = ByteArray(10) { (100 + it).toByte() }
        eeprom.write(0x05, data10)
        val writes = connection.writes()
        val page1Chunk = writes[writes.size - 1]
        val page0Chunk = writes[writes.size - 2]
        assertArrayEquals(byteArrayOf(0x05, 100, 101, 102), page0Chunk)
        assertArrayEquals(byteArrayOf(0x08, 103, 104, 105, 106, 107, 108, 109.toByte()), page1Chunk)
        assertEquals(100, connection.registers()[0x05])
        assertEquals(101, connection.registers()[0x06])
        assertEquals(102, connection.registers()[0x07])
        assertEquals(103, connection.registers()[0x08])
        assertEquals(109, connection.registers()[0x0E])

        connection.setRegister(Eeprom24Aa02UidMinimal.ADDR_MFR_CODE, 0x29)
        assertEquals(0x29, eeprom.readManufacturerCode())

        connection.setRegister(Eeprom24Aa02UidMinimal.ADDR_DEV_CODE, 0x41)
        assertEquals(0x41, eeprom.readDeviceCode())
    }
}
