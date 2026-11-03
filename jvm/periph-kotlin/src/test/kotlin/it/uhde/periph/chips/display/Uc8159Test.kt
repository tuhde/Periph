package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockDisplayConnection
import it.uhde.periph.connection.WireMode
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Uc8159Test {

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun assertCommand(m: MockDisplayConnection, index: Int, cmd: Int, vararg data: Int) {
        assertEquals(cmd, m.commands()[index])
        assertArrayEquals(b(*data), m.commandData(index))
    }

    @Test
    fun initSequence() {
        val m = MockDisplayConnection()
        val d = Uc8159Minimal(m)
        assertEquals("reset", m.events()[0])
        assertEquals("waitBusy:false:60000", m.events()[1])
        assertEquals(10, m.commands().size)
        assertCommand(m, 0, 0x00, 0xEF, 0x08)
        assertCommand(m, 1, 0x01, 0x37, 0x00, 0x23, 0x23)
        assertCommand(m, 2, 0x03, 0x00)
        assertCommand(m, 3, 0x06, 0xC7, 0xC7, 0x1D)
        assertCommand(m, 4, 0x30, 0x3C)
        assertCommand(m, 5, 0x41, 0x00)
        assertCommand(m, 6, 0x50, 0x37)
        assertCommand(m, 7, 0x60, 0x22)
        assertCommand(m, 8, 0x61, 0x02, 0x58, 0x01, 0xC0)
        assertCommand(m, 9, 0xE3, 0xAA)
        assertEquals(600, d.width)
        assertEquals(448, d.height)
        val bad = MockDisplayConnection()
        bad.setReady(false)
        assertThrows(IOException::class.java) { Uc8159Minimal(bad) }
    }

    @Test
    fun framebufferMapping() {
        val d = Uc8159Full(MockDisplayConnection())
        assertEquals(Uc8159Minimal.WHITE, d.getPixel(0, 0))
        for (c in 0..7) d.setPixel(7 + c, 3, c)
        for (c in 0..7) assertEquals(c, d.getPixel(7 + c, 3))
        assertEquals(Uc8159Minimal.WHITE, d.getPixel(6, 3))
        d.setPixel(599, 447, Uc8159Minimal.ORANGE)
        assertEquals(Uc8159Minimal.ORANGE, d.getPixel(599, 447))
        assertEquals(Uc8159Minimal.WHITE, d.getPixel(598, 447))
        d.setPixel(-1, 0, Uc8159Minimal.RED)
        d.setPixel(600, 0, Uc8159Minimal.RED)
        d.setPixel(0, 448, Uc8159Minimal.RED)
        assertEquals(Uc8159Minimal.WHITE, d.getPixel(0, 0))
        assertEquals(Uc8159Minimal.BLACK, d.getPixel(700, 9))
        d.clear(Uc8159Minimal.GREEN)
        assertEquals(Uc8159Minimal.GREEN, d.getPixel(333, 222))
    }

    @Test
    fun showSequence() {
        val m = MockDisplayConnection()
        val d = Uc8159Minimal(m, 1000)
        d.setPixel(0, 0, Uc8159Minimal.BLACK)
        d.setPixel(1, 0, Uc8159Minimal.ORANGE)
        d.setPixel(599, 447, Uc8159Minimal.RED)
        val n = m.events().size
        d.show()
        val tail = m.events().subList(n, m.events().size)
        assertEquals("command:10:", tail[0])
        val frame = tail.filter { it.startsWith("data:") }.joinToString("") { it.substring(5) }
        assertEquals(134400 * 2, frame.length)
        assertTrue(frame.startsWith("0611"))
        assertTrue(frame.endsWith("14"))
        val t = tail.size
        assertEquals("command:04:", tail[t - 6])
        assertEquals("waitBusy:false:1000", tail[t - 5])
        assertEquals("command:12:", tail[t - 4])
        assertEquals("command:02:", tail[t - 2])
    }

    @Test
    fun sleepWakeAndTimeout() {
        val m = MockDisplayConnection()
        val d = Uc8159Minimal(m, 0)
        d.sleep()
        assertCommand(m, m.commands().size - 1, 0x07, 0xA5)
        d.show()
        assertEquals(2, m.events().count { it == "reset" })
        m.setReady(false)
        assertThrows(IOException::class.java) { d.show() }
    }

    @Test
    fun settingsEncodings() {
        val m = MockDisplayConnection()
        val d = Uc8159Full(m, 0)
        val base = m.commands().size
        d.setFlip(true, false)
        d.setFlip(false, true)
        d.setFlip(false, false)
        d.setFrameRate(0x3A)
        d.setInterval(7)
        d.setInterval(3, Uc8159Minimal.BLACK, 0)
        d.setTemperatureSensor(true, -3)
        d.setVcomDc(-0.10)
        d.setVcomDc(-2.0)
        d.setVcomDc(-9.0)
        d.sendCommand(0x13, b(0))
        assertCommand(m, base, 0x00, 0xEB, 0x08)
        assertCommand(m, base + 1, 0x00, 0xE7, 0x08)
        assertCommand(m, base + 2, 0x00, 0xEF, 0x08)
        assertCommand(m, base + 3, 0x30, 0x3A)
        assertCommand(m, base + 4, 0x50, 0x37)
        assertCommand(m, base + 5, 0x50, 0x03)
        assertCommand(m, base + 6, 0x41, 0x8D)
        assertCommand(m, base + 7, 0x82, 0x02)
        assertCommand(m, base + 8, 0x82, 0x28)
        assertCommand(m, base + 9, 0x82, 0x50)
        assertCommand(m, base + 10, 0x13, 0x00)
    }

    @Test
    fun drawingPrimitives() {
        val d = Uc8159Full(MockDisplayConnection(), 0)
        d.drawLine(0, 0, 9, 9, Uc8159Minimal.RED)
        assertEquals(Uc8159Minimal.RED, d.getPixel(9, 9))
        assertEquals(Uc8159Minimal.WHITE, d.getPixel(5, 4))
        d.clear()
        d.drawRect(1, 1, 5, 4, Uc8159Minimal.BLUE)
        assertEquals(Uc8159Minimal.BLUE, d.getPixel(5, 4))
        assertEquals(Uc8159Minimal.WHITE, d.getPixel(3, 2))
        d.drawRect(10, 1, 3, 3, Uc8159Minimal.YELLOW, true)
        assertEquals(Uc8159Minimal.YELLOW, d.getPixel(11, 2))
        d.clear()
        d.blit(4, 2, b(0xA0, 0x40), 4, 2, Uc8159Minimal.GREEN)
        assertEquals(Uc8159Minimal.GREEN, d.getPixel(4, 2))
        assertEquals(Uc8159Minimal.WHITE, d.getPixel(5, 2))
        assertEquals(Uc8159Minimal.GREEN, d.getPixel(5, 3))
    }

    @Test
    fun reads() {
        val m = MockDisplayConnection(WireMode.HALF_DUPLEX_PIN)
        val d = Uc8159Full(m, 0)
        m.queueRead(*b(0x0F))
        assertEquals(0x0F, d.readStatus())
        m.queueRead(*b(0xE7, 0x00))
        assertEquals(-25, d.readTemperature())
        m.queueRead(*b(0x01))
        assertTrue(!d.readLowPower())
        m.queueRead(*b(0x0C))
        assertEquals(-0.60, d.readVcom(), 1e-9)
        val simplex = Uc8159Full(MockDisplayConnection(), 0)
        assertThrows(UnsupportedOperationException::class.java) { simplex.readStatus() }
    }
}
