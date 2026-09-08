package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Pcf8574Test {

    // PCF8574 has no sub-registers: every transaction is a single plain
    // byte read()/write() (no register pointer), so MockConnection's
    // register map is never consulted — reads must be preloaded via
    // queueRead() in the exact order the driver will issue them.
    @Test
    fun fullApi() {
        val connection = MockConnection()
        val chip = Pcf8574Full(connection)

        // Construction writes 0xFF (all pins to quasi-bidirectional input mode).
        assertEquals(1, connection.writes()[0].size)
        assertEquals(0xFF.toByte(), connection.writes()[0][0])

        // readPort(): plain single-byte read.
        connection.queueRead(byteArrayOf(0x5A))
        assertEquals(0x5A, chip.readPort())

        // writePort(): plain single-byte write; updates shadow.
        chip.writePort(0x3C)
        assertEquals(0x3C.toByte(), connection.writes().last()[0])

        // pin().read() reads the live bus level (not the shadow).
        val pin3 = chip.pin(3)
        connection.queueRead(byteArrayOf(0x08)) // bit 3 high
        assertTrue(pin3.read())

        // Pin set high/low preserves other shadow bits (read-modify-write).
        chip.writePort(0xFF)
        pin3.setLow()
        assertEquals((0xFF and 0x08.inv()).toByte(), connection.writes().last()[0])
        val pin5 = chip.pin(5)
        pin5.setLow()
        assertEquals((0xFF and 0x08.inv() and 0x20.inv()).toByte(), connection.writes().last()[0])
        pin3.setHigh()
        assertEquals((0xFF and 0x20.inv()).toByte(), connection.writes().last()[0])

        // Toggle.
        pin3.toggle()
        assertEquals((0xFF and 0x20.inv() and 0x08.inv()).toByte(), connection.writes().last()[0])
        pin3.toggle()
        assertEquals((0xFF and 0x20.inv()).toByte(), connection.writes().last()[0])

        // setInput()/setOutput() releases high (input) or drives low (output).
        val pin0 = chip.pin(0)
        pin0.setOutput()
        assertEquals(0, connection.writes().last()[0].toInt() and 0x01)
        pin0.setInput()
        assertEquals(1, connection.writes().last()[0].toInt() and 0x01)

        // Full: pollInterrupt() compares to the previous read and returns
        // the changed-pin bitmask, also updating the stored previous value.
        connection.queueRead(byteArrayOf(0xFF.toByte()))
        chip.pollInterrupt() // resync prev to a known value (0xFF)
        connection.queueRead(byteArrayOf(0xF7.toByte())) // bit 3 now low
        assertEquals(0x08, chip.pollInterrupt())
        connection.queueRead(byteArrayOf(0xF7.toByte())) // no further change
        assertEquals(0x00, chip.pollInterrupt())
    }
}
