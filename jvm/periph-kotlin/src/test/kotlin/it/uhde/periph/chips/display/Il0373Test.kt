package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockDisplayConnection
import it.uhde.periph.connection.WireMode
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Il0373Test {

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun assertCommand(m: MockDisplayConnection, index: Int, cmd: Int, vararg data: Int) {
        assertEquals(cmd, m.commands()[index])
        assertArrayEquals(b(*data), m.commandData(index))
    }

    @Test
    fun initSequenceDefaultSize() {
        val m = MockDisplayConnection()
        val d = Il0373Minimal(m)
        assertEquals("reset", m.events()[0])
        assertEquals("waitBusy:false:30000", m.events()[1])
        assertEquals(6, m.commands().size)
        assertCommand(m, 0, 0x01, 0x03, 0x00, 0x2B, 0x2B, 0x03)
        assertCommand(m, 1, 0x06, 0x17, 0x17, 0x17)
        assertCommand(m, 2, 0x04)
        assertCommand(m, 3, 0x00, 0x1F)
        assertCommand(m, 4, 0x61, 0x68, 0x00, 0xD4)
        assertCommand(m, 5, 0x50, 0x97)
        assertEquals("waitBusy:false:200", m.events()[5])
        assertEquals(104, d.width)
        assertEquals(212, d.height)
    }

    @Test
    fun initTresForLargestPanel() {
        val m = MockDisplayConnection()
        Il0373Minimal(m, 160, 296, 0)
        assertCommand(m, 4, 0x61, 0xA0, 0x01, 0x28)
    }

    @Test
    fun invalidSizesAndTimeout() {
        val m = MockDisplayConnection()
        assertThrows(IllegalArgumentException::class.java) { Il0373Minimal(m, 101, 212, 0) }
        assertThrows(IllegalArgumentException::class.java) { Il0373Minimal(m, 168, 212, 0) }
        assertThrows(IllegalArgumentException::class.java) { Il0373Minimal(m, 104, 297, 0) }
        val bad = MockDisplayConnection()
        bad.setReady(false)
        assertThrows(IOException::class.java) { Il0373Minimal(bad) }
    }

    @Test
    fun framebufferMapping() {
        val d = Il0373Full(MockDisplayConnection(), 16, 2, 0)
        assertEquals(Il0373Minimal.WHITE, d.getPixel(0, 0))
        d.setPixel(0, 0, Il0373Minimal.BLACK)
        d.setPixel(9, 1, Il0373Minimal.BLACK)
        assertEquals(Il0373Minimal.BLACK, d.getPixel(0, 0))
        assertEquals(Il0373Minimal.BLACK, d.getPixel(9, 1))
        d.setPixel(-1, 0)
        d.setPixel(16, 0)
        d.setPixel(0, 2)
        d.setPixel(0, 0, Il0373Minimal.WHITE)
        assertEquals(Il0373Minimal.WHITE, d.getPixel(0, 0))
    }

    @Test
    fun showSequence() {
        val m = MockDisplayConnection()
        val d = Il0373Minimal(m, 16, 2, 1000)
        d.setPixel(0, 0, Il0373Minimal.BLACK)
        var n = m.events().size
        d.show()
        var tail = m.events().subList(n, m.events().size)
        assertEquals("command:10:", tail[0])
        assertEquals("data:80000000", tail[1])
        assertEquals("command:13:", tail[2])
        assertEquals("data:7fffffff", tail[3])
        assertEquals("command:12:", tail[4])
        assertEquals("waitBusy:false:1000", tail[5])
        assertEquals("command:50:f7", tail[6])
        assertEquals("command:02:", tail[7])
        assertEquals(9, tail.size)
        n = m.events().size
        d.show()
        tail = m.events().subList(n, m.events().size)
        assertEquals("command:04:", tail[0])
        assertEquals("waitBusy:false:200", tail[1])
        assertEquals("command:50:97", tail[2])
        assertEquals("command:10:", tail[3])
    }

    @Test
    fun timeoutThrows() {
        val m = MockDisplayConnection()
        val d = Il0373Minimal(m, 16, 2, 0)
        m.setReady(false)
        assertThrows(IOException::class.java) { d.show() }
    }

    @Test
    fun sleepThenShowWakes() {
        val m = MockDisplayConnection()
        val d = Il0373Minimal(m, 16, 2, 0)
        d.sleep()
        val l = m.commands().size
        assertCommand(m, l - 3, 0x50, 0xF7)
        assertCommand(m, l - 2, 0x02)
        assertCommand(m, l - 1, 0x07, 0xA5)
        d.show()
        assertEquals(2, m.events().count { it == "reset" })
    }

    @Test
    fun settingsEncodings() {
        val m = MockDisplayConnection()
        val d = Il0373Full(m, 16, 2, 0)
        val base = m.commands().size
        d.setFlip(true, false)
        d.setPower(14.0, 14.0, 20.0)
        d.setPower(6.4, 15.0, 17.0)
        d.setVcomDc(-1.0)
        d.setVcomDc(-9.0)
        d.setFrameRate(50)
        d.setFrameRate(200)
        d.setFrameRate(30)
        d.setSoftStart(0x17, 0x17, 0x17)
        d.setInterval(7)
        d.setTemperatureSensor(true, -3)
        assertCommand(m, base, 0x00, 0x1B)
        assertCommand(m, base + 1, 0x01, 0x03, 0x00, 0x26, 0x26, 0x03)
        assertCommand(m, base + 2, 0x01, 0x03, 0x03, 0x00, 0x2B, 0x03)
        assertCommand(m, base + 3, 0x82, 0x12)
        assertCommand(m, base + 4, 0x82, 0x3A)
        assertCommand(m, base + 5, 0x30, 0x3C)
        assertCommand(m, base + 6, 0x30, 0x39)
        assertCommand(m, base + 7, 0x30, 0x09)
        assertCommand(m, base + 8, 0x06, 0x17, 0x17, 0x17)
        assertCommand(m, base + 9, 0x50, 0x97)
        assertCommand(m, base + 10, 0x41, 0x8D)
        d.writeLut(0, ByteArray(44))
        d.writeLut(4, ByteArray(42))
        assertEquals(0x20, m.commands()[base + 11])
        assertEquals(44, m.commandData(base + 11).size)
        assertCommand(m, base + 12, 0x00, 0x3B)
        assertEquals(0x24, m.commands()[base + 13])
        assertThrows(IllegalArgumentException::class.java) { d.writeLut(5, ByteArray(0)) }
    }

    @Test
    fun drawingPrimitives() {
        val d = Il0373Full(MockDisplayConnection(), 16, 2, 0)
        d.drawLine(0, 0, 1, 1)
        assertEquals(Il0373Minimal.BLACK, d.getPixel(1, 1))
        d.drawRect(4, 0, 4, 2, Il0373Minimal.BLACK, true)
        assertEquals(Il0373Minimal.BLACK, d.getPixel(4, 0))
        assertEquals(Il0373Minimal.BLACK, d.getPixel(7, 1))
        d.clear()
        d.drawRect(0, 0, 4, 2)
        assertEquals(Il0373Minimal.BLACK, d.getPixel(3, 1))
        assertEquals(Il0373Minimal.WHITE, d.getPixel(4, 0))
        d.clear()
        d.blit(8, 0, b(0xA0), 4, 1)
        assertEquals(Il0373Minimal.BLACK, d.getPixel(8, 0))
        assertEquals(Il0373Minimal.WHITE, d.getPixel(9, 0))
        assertEquals(Il0373Minimal.BLACK, d.getPixel(10, 0))
        d.clear(Il0373Minimal.BLACK)
        assertEquals(Il0373Minimal.BLACK, d.getPixel(5, 1))
    }

    @Test
    fun partialWindow() {
        val m = MockDisplayConnection()
        val d = Il0373Full(m, 32, 4, 0)
        val n = m.events().size
        d.showRegion(9, 1, 6, 2)
        val tail = m.events().subList(n, m.events().size)
        assertEquals("command:91:", tail[0])
        assertEquals("command:90:080f0001000200", tail[1])
        assertEquals("command:10:", tail[2])
        assertEquals("data:00", tail[3])
        assertEquals("command:13:", tail[5])
        assertEquals("data:ff", tail[6])
        assertEquals("command:92:", tail.last())
        assertThrows(IllegalArgumentException::class.java) { d.showRegion(0, 0, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { d.showRegion(40, 0, 4, 4) }
        assertTrue(d.waitBusy(10))
        assertEquals("waitBusy:false:10", m.events().last())
    }

    @Test
    fun reads() {
        val m = MockDisplayConnection(WireMode.HALF_DUPLEX_PIN)
        val d = Il0373Full(m, 16, 2, 0)
        m.queueRead(*b(0x05))
        assertEquals(5, d.readStatus())
        m.queueRead(*b(0xE7, 0x00))
        assertEquals(-25, d.readTemperature())
        m.queueRead(*b(0x19))
        assertEquals(0x19, d.readRevision())
        m.queueRead(*b(0x0A))
        assertEquals(-0.60, d.readVcom(), 1e-9)
        m.queueRead(*b(0x00))
        assertTrue(d.readLowPower())
        m.queueRead(*b(0xFF, 0x11, 0x22))
        assertArrayEquals(b(0x11, 0x22), d.readOtp(2))

        val simplex = Il0373Full(MockDisplayConnection(), 16, 2, 0)
        assertThrows(UnsupportedOperationException::class.java) { simplex.readStatus() }
    }
}
