package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Pcf8575Test {

    // PCF8575 has no sub-registers: every transaction is a plain 2-byte
    // read()/write() (Port 0 first, Port 1 second; no register pointer), so
    // MockConnection's register map is never consulted — reads must be
    // preloaded via queueRead() in the exact order the driver will issue
    // them. Pcf8575Full's constructor issues one extra 2-byte read to seed
    // `prev`, so it must be queued too.
    @Test
    fun fullApi() {
        val connection = MockConnection()
        connection.queueRead(byteArrayOf(0xFF.toByte(), 0xFF.toByte())) // Full ctor seeds prev
        val chip = Pcf8575Full(connection)

        val first = connection.writes()[0]
        assertEquals(2, first.size)
        assertEquals(0xFF.toByte(), first[0])
        assertEquals(0xFF.toByte(), first[1])

        // readPort(0)/(1): both derived from one 2-byte read.
        connection.queueRead(byteArrayOf(0x5A, 0xA5.toByte()))
        assertEquals(0x5A, chip.readPort(0))
        connection.queueRead(byteArrayOf(0x5A, 0xA5.toByte()))
        assertEquals(0xA5, chip.readPort(1))

        // writePort(): writes both shadow bytes, preserving the untouched port.
        chip.writePort(0, 0x3C)
        var last = connection.writes().last()
        assertEquals(0x3C.toByte(), last[0])
        assertEquals(0xFF.toByte(), last[1])
        chip.writePort(1, 0x0F)
        last = connection.writes().last()
        assertEquals(0x3C.toByte(), last[0])
        assertEquals(0x0F.toByte(), last[1])

        // pin() read on Port 0 and Port 1.
        val pin3 = chip.pin(3)   // Port 0, bit 3
        connection.queueRead(byteArrayOf(0x08, 0x00))
        assertTrue(pin3.read())

        val pin11 = chip.pin(11) // Port 1, bit 3
        connection.queueRead(byteArrayOf(0x00, 0x08))
        assertTrue(pin11.read())

        // Pin set high/low preserves other shadow bits within the same port.
        chip.writePort(0, 0xFF)
        chip.writePort(1, 0xFF)
        pin3.setLow()
        last = connection.writes().last()
        assertEquals((0xFF and 0x08.inv()).toByte(), last[0])
        assertEquals(0xFF.toByte(), last[1])
        val pin5 = chip.pin(5)
        pin5.setLow()
        last = connection.writes().last()
        assertEquals((0xFF and 0x08.inv() and 0x20.inv()).toByte(), last[0])
        assertEquals(0xFF.toByte(), last[1])
        pin11.setLow()
        last = connection.writes().last()
        assertEquals((0xFF and 0x08.inv() and 0x20.inv()).toByte(), last[0])
        assertEquals((0xFF and 0x08.inv()).toByte(), last[1])

        // Toggle.
        pin3.toggle()
        last = connection.writes().last()
        assertEquals(0x08, last[0].toInt() and 0x08)
        pin3.toggle()
        last = connection.writes().last()
        assertEquals(0, last[0].toInt() and 0x08)

        // setInput()/setOutput() releases high (input) or drives low (output).
        val pin0 = chip.pin(0)
        pin0.setOutput()
        last = connection.writes().last()
        assertEquals(0, last[0].toInt() and 0x01)
        pin0.setInput()
        last = connection.writes().last()
        assertEquals(1, last[0].toInt() and 0x01)

        // Full: pollInterrupt() compares to the previous 2-byte read and
        // returns the 16-bit changed-pin bitmask (bits 0-7 = Port 0, bits
        // 8-15 = Port 1).
        connection.queueRead(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        chip.pollInterrupt() // resync prev to a known value
        connection.queueRead(byteArrayOf(0xF7.toByte(), 0xFE.toByte())) // Port0 bit3 low, Port1 bit0 low
        assertEquals(0x08 or (0x01 shl 8), chip.pollInterrupt())
        connection.queueRead(byteArrayOf(0xF7.toByte(), 0xFE.toByte())) // no further change
        assertEquals(0x00, chip.pollInterrupt())
    }
}
