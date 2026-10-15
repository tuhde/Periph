package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockDisplayConnection
import it.uhde.periph.connection.WireMode
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Uc8276cTest {

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun assertCommand(m: MockDisplayConnection, index: Int, cmd: Int, vararg data: Int) {
        assertEquals(cmd, m.commands()[index])
        assertArrayEquals(b(*data), m.commandData(index))
    }

    @Test
    fun initSequenceDefaultSize() {
        val m = MockDisplayConnection()
        Uc8276cMinimal(m)
        assertEquals("reset", m.events()[0])
        assertEquals("waitBusy:false:30000", m.events()[1])
        assertEquals(2, m.commands().size)
        assertCommand(m, 0, 0x00, 0x0F)
        assertCommand(m, 1, 0x50, 0xD7)
    }

    @Test
    fun initSendsTresForSmallerPanel() {
        val m = MockDisplayConnection()
        Uc8276cMinimal(m, 200, 100, 0)
        assertCommand(m, 1, 0x61, 0x00, 0xC8, 0x00, 0x64)
    }

    @Test
    fun invalidSizesAndTimeout() {
        val m = MockDisplayConnection()
        assertThrows(IllegalArgumentException::class.java) { Uc8276cMinimal(m, 401, 300, 0) }
        assertThrows(IllegalArgumentException::class.java) { Uc8276cMinimal(m, 400, 301, 0) }
        val bad = MockDisplayConnection()
        bad.setReady(false)
        assertThrows(IOException::class.java) { Uc8276cMinimal(bad) }
    }

    @Test
    fun framebufferMapping() {
        val d = Uc8276cFull(MockDisplayConnection(), 16, 2, 0)
        assertEquals(Uc8276cMinimal.WHITE, d.getPixel(0, 0))
        d.setPixel(0, 0, Uc8276cMinimal.BLACK)
        d.setPixel(9, 1, Uc8276cMinimal.RED)
        assertEquals(Uc8276cMinimal.BLACK, d.getPixel(0, 0))
        assertEquals(Uc8276cMinimal.RED, d.getPixel(9, 1))
        d.setPixel(-1, 0)
        d.setPixel(16, 0)
        d.setPixel(0, 2)
        d.setPixel(0, 0, Uc8276cMinimal.WHITE)
        assertEquals(Uc8276cMinimal.WHITE, d.getPixel(0, 0))
    }

    @Test
    fun showSequence() {
        val m = MockDisplayConnection()
        val d = Uc8276cMinimal(m, 16, 2, 1000)
        d.setPixel(0, 0, Uc8276cMinimal.RED)
        val n = m.events().size
        d.show()
        val tail = m.events().subList(n, m.events().size)
        assertEquals("command:10:", tail[0])
        assertEquals("data:7fffffff", tail[1])
        assertEquals("command:13:", tail[2])
        assertEquals("data:80000000", tail[3])
        assertEquals("command:04:", tail[4])
        assertEquals("waitBusy:false:1000", tail[5])
        assertEquals("command:12:", tail[6])
        assertEquals("command:02:", tail[8])
        assertEquals(10, tail.size)
    }

    @Test
    fun showWithoutRedSendsZerosAndTimeoutThrows() {
        val m = MockDisplayConnection()
        val d = Uc8276cMinimal(m, 16, 2, 0)
        val n = m.events().size
        d.show()
        assertEquals("data:00000000", m.events()[n + 3])
        m.setReady(false)
        assertThrows(IOException::class.java) { d.show() }
    }

    @Test
    fun sleepThenShowWakes() {
        val m = MockDisplayConnection()
        val d = Uc8276cMinimal(m, 16, 2, 0)
        d.sleep()
        assertCommand(m, m.commands().size - 1, 0x07, 0xA5)
        d.show()
        assertEquals(2, m.events().count { it == "reset" })
    }

    @Test
    fun settingsEncodings() {
        val m = MockDisplayConnection()
        val d = Uc8276cFull(m, 16, 2, 0)
        val base = m.commands().size
        d.setMode(Uc8276cMinimal.MODE_KW)
        d.setFlip(true, false)
        d.setPower(10.0, 10.0, 10.0, 16.0)
        d.setPower(2.4, 11.0, 5.0, 13.0)
        d.setVcomDc(-1.0)
        d.setFrameRate(50)
        d.setFrameRate(200)
        d.setFrameRate(30)
        d.setSoftStart(0x17, 0x17, 0x17)
        d.setInterval(7)
        d.setTemperatureSensor(true, -3)
        assertCommand(m, base, 0x00, 0x1F)
        assertCommand(m, base + 1, 0x00, 0x1B)
        assertCommand(m, base + 2, 0x01, 0x03, 0x00, 0x26, 0x26, 0x26)
        assertCommand(m, base + 3, 0x01, 0x03, 0x03, 0x00, 0x2B, 0x0D)
        assertCommand(m, base + 4, 0x82, 0x12)
        assertCommand(m, base + 5, 0x30, 0x3C)
        assertCommand(m, base + 6, 0x30, 0x39)
        assertCommand(m, base + 7, 0x30, 0x09)
        assertCommand(m, base + 8, 0x06, 0x17, 0x17, 0x17)
        assertCommand(m, base + 9, 0x50, 0xD7)
        assertCommand(m, base + 10, 0x41, 0x8D)
        d.writeLut(ByteArray(56), null, ByteArray(56), null, null)
        assertEquals(0x20, m.commands()[base + 11])
        assertEquals(0x22, m.commands()[base + 12])
        assertCommand(m, base + 13, 0x00, 0x3B)
    }

    @Test
    fun drawingPrimitives() {
        val d = Uc8276cFull(MockDisplayConnection(), 16, 2, 0)
        d.drawLine(0, 0, 1, 1)
        assertEquals(Uc8276cMinimal.BLACK, d.getPixel(1, 1))
        d.drawRect(4, 0, 4, 2, Uc8276cMinimal.RED, true)
        assertEquals(Uc8276cMinimal.RED, d.getPixel(4, 0))
        assertEquals(Uc8276cMinimal.RED, d.getPixel(7, 1))
        d.clear()
        d.drawRect(0, 0, 4, 2)
        assertEquals(Uc8276cMinimal.BLACK, d.getPixel(3, 1))
        assertEquals(Uc8276cMinimal.WHITE, d.getPixel(4, 0))
        d.clear()
        d.blit(8, 0, b(0xA0), 4, 1)
        assertEquals(Uc8276cMinimal.BLACK, d.getPixel(8, 0))
        assertEquals(Uc8276cMinimal.WHITE, d.getPixel(9, 0))
        assertEquals(Uc8276cMinimal.BLACK, d.getPixel(10, 0))
        d.clear(Uc8276cMinimal.RED)
        assertEquals(Uc8276cMinimal.RED, d.getPixel(5, 1))
    }

    @Test
    fun partialWindow() {
        val m = MockDisplayConnection()
        val d = Uc8276cFull(m, 32, 4, 0)
        val n = m.events().size
        d.showRegion(9, 1, 6, 2)
        val tail = m.events().subList(n, m.events().size)
        assertEquals("command:91:", tail[0])
        assertEquals("command:90:0008000f0001000200", tail[1])
        assertEquals("command:10:", tail[2])
        assertEquals("data:ff", tail[3])
        assertEquals("command:92:", tail.last())
        assertThrows(IllegalArgumentException::class.java) { d.showRegion(0, 0, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { d.showRegion(40, 0, 4, 4) }
    }

    @Test
    fun autoAndWaitBusy() {
        val m = MockDisplayConnection()
        val d = Uc8276cFull(m, 16, 2, 0)
        d.refreshAuto()
        assertCommand(m, m.commands().size - 1, 0x17, 0xA5)
        d.refreshAuto(true)
        assertCommand(m, m.commands().size - 1, 0x17, 0xA7)
        assertTrue(d.waitBusy(10))
        assertEquals("waitBusy:false:10", m.events().last())
    }

    @Test
    fun reads() {
        val m = MockDisplayConnection(WireMode.HALF_DUPLEX_PIN)
        val d = Uc8276cFull(m, 16, 2, 0)
        m.queueRead(*b(0x05))
        assertEquals(5, d.readStatus())
        m.queueRead(*b(0xE7, 0x00))
        assertEquals(-25, d.readTemperature())
        m.queueRead(*b(0x19))
        assertArrayEquals(intArrayOf(-1, 0x19), d.readRevision())
        m.queueRead(*b(0x00, 0x02, 0x01, 0x02, 0x03))
        assertArrayEquals(intArrayOf(2, 0x030201), d.readRevision(true))
        m.queueRead(*b(0x0A))
        assertEquals(-0.60, d.readVcom(), 1e-9)
        m.queueRead(*b(0x00))
        assertTrue(d.readLowPower())
        m.queueRead(*b(0xFF, 0x11, 0x22))
        assertArrayEquals(b(0x11, 0x22), d.readOtp(2))

        val simplex = Uc8276cFull(MockDisplayConnection(), 16, 2, 0)
        assertThrows(UnsupportedOperationException::class.java) { simplex.readStatus() }
    }
}
