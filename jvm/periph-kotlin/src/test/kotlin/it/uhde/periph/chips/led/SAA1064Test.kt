package it.uhde.periph.chips.led

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SAA1064Test {

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun lastWrite(c: MockConnection): ByteArray = c.writes().last()

    @Test
    fun fullApi() {
        val c = MockConnection()
        val d = SAA1064Full(c)

        assertArrayEquals(b(0x00, 0x47, 0, 0, 0, 0), c.writes()[0])

        d.showDigits(intArrayOf(1, 2, 3, 4))
        assertArrayEquals(b(0x01, 0x06, 0x5B, 0x4F, 0x66), lastWrite(c))
        d.showDigits(intArrayOf(SAA1064Minimal.BLANK, 0, 9, 8))
        assertArrayEquals(b(0x01, 0x00, 0x3F, 0x6F, 0x7F), lastWrite(c))
        assertThrows(IllegalArgumentException::class.java) { d.showDigits(intArrayOf(10, 0, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { d.showDigits(intArrayOf(1, 2, 3)) }

        d.showNumber(42)
        assertArrayEquals(b(0x01, 0x00, 0x00, 0x66, 0x5B), lastWrite(c))
        d.showNumber(0)
        assertArrayEquals(b(0x01, 0x00, 0x00, 0x00, 0x3F), lastWrite(c))
        d.showNumber(9999)
        assertArrayEquals(b(0x01, 0x6F, 0x6F, 0x6F, 0x6F), lastWrite(c))
        assertThrows(IllegalArgumentException::class.java) { d.showNumber(10000) }
        assertThrows(IllegalArgumentException::class.java) { d.showNumber(-1) }

        d.clear()
        assertArrayEquals(b(0x01, 0, 0, 0, 0), lastWrite(c))
        d.writeRaw(intArrayOf(0xAA, 0x55), 2)
        assertArrayEquals(b(0x02, 0xAA, 0x55), lastWrite(c))
        assertThrows(IllegalArgumentException::class.java) { d.writeRaw(intArrayOf(1, 2, 3), 3) }
        assertThrows(IllegalArgumentException::class.java) { d.writeRaw(intArrayOf(1), 0) }
        assertThrows(IllegalArgumentException::class.java) { d.writeRaw(intArrayOf(), 1) }

        d.setMode(false)
        assertArrayEquals(b(0x00, 0x46), lastWrite(c))
        d.setMode(true)
        assertArrayEquals(b(0x00, 0x47), lastWrite(c))
        d.blank(true, false)
        assertArrayEquals(b(0x00, 0x45), lastWrite(c))
        d.blank(true, true)
        assertArrayEquals(b(0x00, 0x41), lastWrite(c))
        d.blank(false, false)
        assertArrayEquals(b(0x00, 0x47), lastWrite(c))
        d.setCurrent(21)
        assertArrayEquals(b(0x00, 0x77), lastWrite(c))
        d.setCurrent(0)
        assertArrayEquals(b(0x00, 0x07), lastWrite(c))
        d.setCurrent(12)
        assertArrayEquals(b(0x00, 0x47), lastWrite(c))
        assertThrows(IllegalArgumentException::class.java) { d.setCurrent(10) }
        d.segmentTest(true)
        assertArrayEquals(b(0x00, 0x4F), lastWrite(c))
        d.segmentTest(false)
        assertArrayEquals(b(0x00, 0x47), lastWrite(c))
        d.writeControl(0xFF)
        assertArrayEquals(b(0x00, 0x7F), lastWrite(c))
        d.writeControl(0x47)

        d.showDigit(2, 7, true)
        assertArrayEquals(b(0x02, 0x87), lastWrite(c))
        d.setDecimalPoint(2, false)
        assertArrayEquals(b(0x02, 0x07), lastWrite(c))
        d.setDecimalPoint(3, true)
        assertArrayEquals(b(0x03, 0xD5), lastWrite(c))
        assertThrows(IllegalArgumentException::class.java) { d.showDigit(5, 1) }
        assertThrows(IllegalArgumentException::class.java) { d.setDecimalPoint(0, true) }

        d.setSegmentMap(mapOf("a" to 7, "b" to 6, "c" to 5, "d" to 4, "e" to 3, "f" to 2, "g" to 1, "dp" to 0))
        d.showDigits(intArrayOf(1, 1, 1, 1))
        assertArrayEquals(b(0x01, 0x60, 0x60, 0x60, 0x60), lastWrite(c))
        assertThrows(IllegalArgumentException::class.java) { d.setSegmentMap(mapOf("a" to 0)) }
        assertThrows(IllegalArgumentException::class.java) {
            d.setSegmentMap(mapOf("a" to 8, "b" to 1, "c" to 2, "d" to 3, "e" to 4, "f" to 5, "g" to 6, "dp" to 7))
        }

        c.queueRead(b(0x80))
        assertEquals(0x80, d.readStatus())
        c.queueRead(b(0x80))
        assertTrue(d.powerResetFlag())
        c.queueRead(b(0x00))
        assertFalse(d.powerResetFlag())
    }
}
