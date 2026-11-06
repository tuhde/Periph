package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PCF8578Test {

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun cat(head: IntArray, n: Int, fill: Int): ByteArray =
        ByteArray(head.size + n) { if (it < head.size) head[it].toByte() else fill.toByte() }

    private fun last(c: MockConnection): ByteArray = c.writes().let { it[it.size - 1] }
    private fun prev(c: MockConnection): ByteArray = c.writes().let { it[it.size - 2] }

    @Test
    fun initWritesClearThenDisplayOn() {
        val c = MockConnection()
        PCF8578Full(c)
        assertArrayEquals(cat(intArrayOf(0xE0, 0xD2, 0xFC, 0xF8, 0x00), 160, 0), c.writes()[0])
        assertArrayEquals(b(0x56), c.writes()[1])
        for ((rows, byte) in listOf(8 to 0x55, 24 to 0x57, 32 to 0x54)) {
            val m = MockConnection()
            PCF8578Minimal(m, rows)
            assertArrayEquals(b(byte), m.writes()[1], "rows $rows")
        }
        assertThrows(IllegalArgumentException::class.java) { PCF8578Minimal(MockConnection(), 12) }
    }

    @Test
    fun pixelsAndColumns() {
        val c = MockConnection()
        val lcd = PCF8578Full(c)
        lcd.clear()
        assertArrayEquals(cat(intArrayOf(0xE0, 0xF8, 0x00), 160, 0), last(c))

        lcd.setPixel(0, 0, true)
        assertArrayEquals(b(0xE0, 0xF0, 16, 0x01), last(c))
        lcd.setPixel(3, 9, true)
        assertArrayEquals(b(0xE0, 0xF1, 19, 0x02), last(c))
        lcd.setPixel(3, 10, true)
        assertArrayEquals(b(0xE0, 0xF1, 19, 0x06), last(c))
        lcd.setPixel(3, 9, false)
        assertArrayEquals(b(0xE0, 0xF1, 19, 0x04), last(c))

        val n = c.writes().size
        lcd.setPixel(24, 0)
        lcd.setPixel(0, 16)
        lcd.setPixel(-1, 0)
        assertEquals(n, c.writes().size)
        assertTrue(lcd.getPixel(0, 0))
        assertFalse(lcd.getPixel(3, 9))
        assertEquals(n, c.writes().size)

        lcd.writeColumns(22, 0, b(1, 2, 3, 4))
        assertArrayEquals(b(0xE0, 0xF0, 38, 1, 2), last(c))
        assertThrows(IllegalArgumentException::class.java) { lcd.writeColumns(0, 2, b(1)) }
    }

    @Test
    fun drawing() {
        val c = MockConnection()
        val lcd = PCF8578Full(c)
        lcd.fill(true)
        assertArrayEquals(cat(intArrayOf(0xE0, 0xF0, 16), 24, 0xFF), prev(c))
        assertArrayEquals(cat(intArrayOf(0xE0, 0xF1, 16), 24, 0xFF), last(c))
        lcd.fill(false)
        assertFalse(lcd.getPixel(5, 5))

        lcd.clear()
        val n = c.writes().size
        lcd.drawLine(0, 0, 3, 0)
        assertEquals(n + 1, c.writes().size)
        assertArrayEquals(b(0xE0, 0xF0, 16, 1, 1, 1, 1), last(c))

        lcd.clear()
        lcd.drawRect(0, 0, 3, 3)
        assertTrue(lcd.getPixel(0, 0) && lcd.getPixel(2, 2))
        assertFalse(lcd.getPixel(1, 1))
        lcd.drawRect(0, 0, 3, 3, true, true)
        assertTrue(lcd.getPixel(1, 1))

        lcd.showBitmap(2, 8, arrayOf(b(0xAA, 0xBB)))
        assertArrayEquals(b(0xE0, 0xF1, 18, 0xAA, 0xBB), last(c))
        assertThrows(IllegalArgumentException::class.java) { lcd.showBitmap(0, 3, arrayOf(b(1))) }
    }

    @Test
    fun configuration() {
        val c = MockConnection()
        val lcd = PCF8578Full(c)
        lcd.setDisplay(PCF8578Full.DISPLAY_BLANK)
        assertArrayEquals(b(0x52), last(c))
        lcd.setDisplay(PCF8578Full.DISPLAY_INVERSE)
        assertArrayEquals(b(0x5E), last(c))
        lcd.setDisplay(PCF8578Full.DISPLAY_ALL_ON)
        assertArrayEquals(b(0x5A), last(c))
        lcd.setDisplay(PCF8578Full.DISPLAY_NORMAL)
        assertArrayEquals(b(0x56), last(c))

        lcd.setStartBank(1)
        assertArrayEquals(b(0x7D), last(c))
        lcd.scroll(1)
        assertArrayEquals(b(0x7C), last(c))
        lcd.scroll(-1)
        assertArrayEquals(b(0x7D), last(c))
        assertThrows(IllegalArgumentException::class.java) { lcd.setStartBank(4) }

        lcd.deviceSelect(5)
        assertArrayEquals(b(0x65), last(c))
        assertThrows(IllegalArgumentException::class.java) { lcd.deviceSelect(16) }
        lcd.setPixel(0, 0)
        assertEquals(0xE0.toByte(), last(c)[0])

        lcd.setMode(24)
        assertArrayEquals(b(0x57), last(c))
        lcd.setMode(16, false)
        assertArrayEquals(b(0x46), last(c))
        assertThrows(IllegalStateException::class.java) { lcd.setPixel(0, 0) }
        assertThrows(IllegalStateException::class.java) { lcd.writeRam(0, 0, b(1)) }
        lcd.setMode(16, true)

        lcd.reset()
        assertArrayEquals(cat(intArrayOf(0xE0, 0xD2, 0xFC, 0xF8, 0x00), 160, 0), prev(c))
        assertArrayEquals(b(0x56), last(c))
    }

    @Test
    fun rawRam() {
        val c = MockConnection()
        val lcd = PCF8578Full(c)
        lcd.writeRam(10, 0, b(1, 2), PCF8578Full.ACCESS_CHARACTER)
        assertArrayEquals(b(0xE0, 0xF0, 10, 1, 2), last(c))
        lcd.writeRam(10, 2, b(1, 2, 3, 4), PCF8578Full.ACCESS_HALF_GRAPHIC)
        assertArrayEquals(b(0xE0, 0xF6, 10, 1, 2, 3, 4), last(c))
        lcd.writeRam(10, 0, b(1, 2, 3, 4, 5, 6, 7, 8), PCF8578Full.ACCESS_FULL_GRAPHIC)
        assertArrayEquals(b(0xE0, 0xF8, 10, 1, 2, 3, 4, 5, 6, 7, 8), last(c))
        assertThrows(IllegalArgumentException::class.java) { lcd.writeRam(39, 0, b(0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { lcd.writeRam(0, 4, b(1)) }
        assertThrows(IllegalArgumentException::class.java) { lcd.writeRam(0, 1, b(1), 1) }

        val data = lcd.readRam(16, 0, 4)
        assertArrayEquals(b(0xE0, 0xF0, 16), last(c))
        assertEquals(4, data.size)
        lcd.readRam(5, 2, 3, PCF8578Full.ACCESS_HALF_GRAPHIC)
        assertArrayEquals(b(0xE0, 0xF6, 5), last(c))
    }
}
