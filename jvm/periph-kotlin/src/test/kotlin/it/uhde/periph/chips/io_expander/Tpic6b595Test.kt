package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.MockSiPo
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Tpic6b595Test {

    @Test
    fun constructionClearsAndFlushesAllZero() {
        val connection = MockSiPo()
        val chip = Tpic6b595Minimal(connection)
        assertEquals(1, connection.clearCount())
        assertArrayEquals(byteArrayOf(0x00), connection.writes().last())
        assertEquals(0, chip.shadow[0])
    }

    @Test
    fun constructionWithoutSrclrDoesNotThrow() {
        // Regression: SiPoConnection.clear() throws IllegalStateException when
        // SRCLR is unconfigured; the constructor must swallow it.
        val connection = MockSiPo(false, true)
        Tpic6b595Minimal(connection)
        assertEquals(0, connection.clearCount())
        assertArrayEquals(byteArrayOf(0x00), connection.writes().last())
    }

    @Test
    fun pinSetHighLowToggleRead() {
        val connection = MockSiPo()
        val chip = Tpic6b595Minimal(connection)

        val pin3 = chip.pin(3)
        pin3.setHigh()
        assertEquals(0x08, chip.shadow[0])
        assertArrayEquals(byteArrayOf(0x08), connection.writes().last())
        assertTrue(pin3.read())

        pin3.setLow()
        assertEquals(0x00, chip.shadow[0])
        assertFalse(pin3.read())

        pin3.toggle()
        assertEquals(0x08, chip.shadow[0])
        pin3.toggle()
        assertEquals(0x00, chip.shadow[0])

        val pin5 = chip.pin(5)
        pin5.setHigh()
        assertEquals(0x20, chip.shadow[0])
        pin3.setHigh()
        assertEquals(0x28, chip.shadow[0]) // pin5 preserved
    }

    @Test
    fun writePortFillOff() {
        val connection = MockSiPo()
        val chip = Tpic6b595Minimal(connection)

        chip.writePort(0, 0x3C)
        assertEquals(0x3C, chip.shadow[0])
        assertArrayEquals(byteArrayOf(0x3C), connection.writes().last())

        chip.fill(true)
        assertEquals(0xFF, chip.shadow[0])
        assertArrayEquals(byteArrayOf(0xFF.toByte()), connection.writes().last())

        chip.off()
        assertEquals(0x00, chip.shadow[0])
        assertArrayEquals(byteArrayOf(0x00), connection.writes().last())
    }

    @Test
    fun cascadeWireOrderReversed() {
        val connection = MockSiPo()
        val chip = Tpic6b595Minimal(connection, 3)

        chip.writePort(0, 0xAA)
        chip.writePort(1, 0xBB)
        chip.writePort(2, 0xCC)
        assertArrayEquals(byteArrayOf(0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte()), connection.writes().last())

        val pinFar = chip.pin(16) // device 2, bit 0
        pinFar.setHigh()
        assertEquals(0xCD, chip.shadow[2])
        assertArrayEquals(byteArrayOf(0xCD.toByte(), 0xBB.toByte(), 0xAA.toByte()), connection.writes().last())
    }

    @Test
    fun fullClearAndSetOutputEnable() {
        val connection = MockSiPo()
        val full = Tpic6b595Full(connection)

        full.clear()
        assertEquals(2, connection.clearCount()) // +1 from construction

        full.setOutputEnable(true)
        full.setOutputEnable(false)
        assertEquals(listOf(true, false), connection.outputEnableCalls())
    }

    @Test
    fun fullClearAndSetOutputEnableThrowWhenUnwired() {
        val connection = MockSiPo(false, false)
        val full = Tpic6b595Full(connection)
        assertThrows(IllegalStateException::class.java) { full.clear() }
        assertThrows(IllegalStateException::class.java) { full.setOutputEnable(true) }
    }

    @Test
    fun fullWriteAllZeroExtendsAndTruncates() {
        val connection = MockSiPo()
        val full = Tpic6b595Full(connection, 3)

        full.writeAll(intArrayOf(0x11, 0x22)) // shorter -> zero-extend
        assertEquals(0x11, full.shadow[0])
        assertEquals(0x22, full.shadow[1])
        assertEquals(0x00, full.shadow[2])
        assertArrayEquals(byteArrayOf(0x00, 0x22, 0x11), connection.writes().last())

        full.writeAll(intArrayOf(0x44, 0x55, 0x66, 0x77)) // longer -> truncate
        assertEquals(0x44, full.shadow[0])
        assertEquals(0x55, full.shadow[1])
        assertEquals(0x66, full.shadow[2])
        assertArrayEquals(byteArrayOf(0x66, 0x55, 0x44), connection.writes().last())
    }
}
