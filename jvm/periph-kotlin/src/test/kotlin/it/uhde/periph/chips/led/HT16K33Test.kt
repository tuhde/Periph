package it.uhde.periph.chips.led

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HT16K33Test {

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun lastWrite(c: MockConnection): ByteArray = c.writes().last()

    @Test
    fun fullApi() {
        val c = MockConnection()
        val d = HT16K33Full(c)

        assertArrayEquals(b(0x21), c.writes()[0])
        assertArrayEquals(b(0xA0), c.writes()[1])
        assertArrayEquals(b(0xEF), c.writes()[2])
        assertArrayEquals(ByteArray(17), c.writes()[3])
        assertArrayEquals(b(0x81), c.writes()[4])

        d.setPixel(1, 9)
        d.show()
        val exp = ByteArray(17)
        exp[1 + 3] = 0x02
        assertArrayEquals(exp, lastWrite(c))
        assertTrue(d.getPixel(1, 9))
        assertFalse(d.getPixel(1, 8))
        d.setPixel(7, 15)
        d.setPixel(1, 9, false)
        assertFalse(d.getPixel(1, 9))
        assertTrue(d.getPixel(7, 15))
        assertThrows(IllegalArgumentException::class.java) { d.setPixel(8, 0) }
        assertThrows(IllegalArgumentException::class.java) { d.setPixel(0, 16) }

        d.clear()
        assertArrayEquals(ByteArray(17), lastWrite(c))
        d.writeRaw(b(0xAB, 0xCD), 4)
        assertArrayEquals(b(0x04, 0xAB, 0xCD), lastWrite(c))
        var n = c.writes().size
        assertThrows(IllegalArgumentException::class.java) { d.writeRaw(b(0, 0, 0), 14) }
        assertThrows(IllegalArgumentException::class.java) { d.writeRaw(b(), 0) }
        assertEquals(n, c.writes().size)

        d.setBrightness(7)
        assertArrayEquals(b(0xE7), lastWrite(c))
        assertThrows(IllegalArgumentException::class.java) { d.setBrightness(16) }

        d.setBlink(1.0)
        assertArrayEquals(b(0x85), lastWrite(c))
        d.setBlink(0.5)
        assertArrayEquals(b(0x87), lastWrite(c))
        d.setBlink(2.0)
        assertArrayEquals(b(0x83), lastWrite(c))
        assertThrows(IllegalArgumentException::class.java) { d.setBlink(3.0) }
        d.displayOff()
        assertArrayEquals(b(0x82), lastWrite(c))
        d.displayOn()
        assertArrayEquals(b(0x83), lastWrite(c))
        d.setBlink(0.0)

        d.setIntOutput(true, false)
        assertArrayEquals(b(0xA1), lastWrite(c))
        d.setIntOutput(true, true)
        assertArrayEquals(b(0xA3), lastWrite(c))

        d.standby()
        assertArrayEquals(b(0x20), lastWrite(c))
        n = c.writes().size
        d.wake()
        val w = c.writes()
        assertArrayEquals(b(0x21), w[n])
        assertArrayEquals(b(0xA3), w[n + 1])
        assertArrayEquals(b(0xE7), w[n + 2])
        assertEquals(17, w[n + 3].size)
        assertArrayEquals(b(0x81), w[n + 4])

        c.queueRead(b(0x01, 0x00, 0x00, 0x10, 0x00, 0x00))
        val keys = d.readKeys()
        assertArrayEquals(b(0x40), lastWrite(c))
        assertEquals(1L or (1L shl 25), keys)
        c.queueRead(b(0x01))
        assertTrue(d.keyPressed())
        assertArrayEquals(b(0x60), lastWrite(c))
        c.queueRead(b(0x00))
        assertEquals(0L, d.pollInterrupt())
        c.queueRead(b(0x01))
        c.queueRead(b(0, 0, 0, 0, 0x01, 0))
        assertEquals(1L shl 26, d.pollInterrupt())
    }
}
