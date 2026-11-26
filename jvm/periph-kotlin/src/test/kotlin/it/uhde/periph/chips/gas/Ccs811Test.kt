package it.uhde.periph.chips.gas

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.function.IntConsumer

class Ccs811Test {

    /** Each register is its own mailbox; STATUS tracks FW_MODE and DATA_READY. */
    private class Mock : MockConnection() {
        val mail = mutableMapOf(
            0x20 to intArrayOf(0x81), 0x21 to intArrayOf(0x12),
            0x23 to intArrayOf(0x10, 0x00), 0x24 to intArrayOf(0x11, 0x02),
            0x02 to RESULT, 0x03 to intArrayOf(0x8C, 0x88),
            0x06 to intArrayOf(0x06, 0x40, 0x03, 0x20), 0x11 to intArrayOf(0x84, 0x7B), 0xE0 to intArrayOf(0x00),
        )
        var appValid = true
        var fwMode = false
        var dataReady = true
        var readyAfterStart = true
        var pin: InputPin? = null

        override fun write(data: ByteArray) {
            super.write(data)
            val reg = data[0].toInt() and 0xFF
            when {
                reg == 0xF4 && data.size == 1 -> if (readyAfterStart) fwMode = true
                reg == 0xFF -> fwMode = false
                data.size >= 2 -> mail[reg] = IntArray(data.size - 1) { data[it + 1].toInt() and 0xFF }
            }
        }

        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            super.writeRead(data, n)
            val reg = data[0].toInt() and 0xFF
            val out = ByteArray(n)
            if (reg == 0x00) {
                out[0] = ((if (fwMode) 0x80 else 0) or (if (appValid) 0x10 else 0) or (if (dataReady) 0x08 else 0)).toByte()
                return out
            }
            val v = mail[reg] ?: IntArray(0)
            for (i in 0 until minOf(n, v.size)) out[i] = v[i].toByte()
            if (reg == 0x02) dataReady = false
            return out
        }

        override fun intPin(): InputPin? = pin
    }

    private class FakePin : InputPin {
        var handler: EdgeHandler? = null
        var trigger: EdgeTrigger? = null
        override fun onEdge(h: EdgeHandler, t: EdgeTrigger) { handler = h; trigger = t }
        override fun offEdge(h: EdgeHandler) { if (h === handler) handler = null }
        override fun isHigh(): Boolean = true
        override fun close() {}
    }

    companion object {
        val RESULT = intArrayOf(0x01, 0xF4, 0x00, 0x0F, 0x98, 0x00, 0x8C, 0x88)
    }

    private lateinit var c: Mock

    private fun last(reg: Int): List<Int>? =
        c.writes().lastOrNull { it.size >= 2 && (it[0].toInt() and 0xFF) == reg }
            ?.drop(1)?.map { it.toInt() and 0xFF }

    private fun wrote(m: Mock, from: Int, vararg frame: Int): Boolean =
        m.writes().drop(from).any { w -> w.map { it.toInt() and 0xFF } == frame.toList() }

    private fun make(): Ccs811Full {
        c = Mock()
        val s = Ccs811Full(c)
        assertTrue(c.writes().isEmpty(), "constructor touched the bus")
        s.init()
        return s
    }

    @Test
    fun initStartsApplicationAndReads() {
        val s = make()
        assertTrue(wrote(c, 0, 0xF4))
        assertEquals(listOf(0x10), last(0x01))
        assertEquals(Ccs811Minimal.Measurement(500, 15), s.read())
        assertFalse(c.dataReady)
        c.dataReady = true
        assertEquals(500, s.readEco2())
        c.dataReady = true
        assertEquals(15, s.readTvoc())
        assertFalse(s.dataReady())
        val (cur, volt) = s.readRaw()
        assertEquals(35, cur)
        assertEquals(136 * 1.65 / 1023, volt, 1e-12)
    }

    @Test
    fun modeAndInterruptBits() {
        val s = make()
        s.enableInterrupt(Ccs811Full.SOURCE_DATA_READY)
        assertEquals(listOf(0x18), last(0x01))
        s.setMode(2)
        assertEquals(listOf(0x28), last(0x01))
        s.enableInterrupt(Ccs811Full.SOURCE_THRESHOLD)
        assertEquals(listOf(0x2C), last(0x01))
        s.disableInterrupt(Ccs811Full.SOURCE_THRESHOLD)
        assertEquals(listOf(0x20), last(0x01))
        s.enableInterrupt(Ccs811Full.SOURCE_THRESHOLD)
        s.disableInterrupt(Ccs811Full.SOURCE_DATA_READY)
        assertEquals(listOf(0x24), last(0x01))
        s.disableInterrupt(Ccs811Full.SOURCE_THRESHOLD)
        s.setMode(4)
        assertEquals(listOf(0x40), last(0x01))
        s.setMode(0)
        assertEquals(listOf(0x00), last(0x01))
        assertThrows(IllegalArgumentException::class.java) { s.setMode(5) }
    }

    @Test
    fun environmentThresholdsBaseline() {
        val s = make()
        s.setEnvironment(48.5, 22.5)
        assertEquals(listOf(0x61, 0x00, 0x5F, 0x00), last(0x05))
        s.setEnvironment(50.0, 25.0)
        assertEquals(listOf(0x64, 0x00, 0x64, 0x00), last(0x05))
        assertThrows(IllegalArgumentException::class.java) { s.setEnvironment(101.0, 25.0) }
        assertThrows(IllegalArgumentException::class.java) { s.setEnvironment(50.0, -26.0) }
        s.setThresholds(1000, 2000, 50)
        assertEquals(listOf(0x03, 0xE8, 0x07, 0xD0, 0x32), last(0x10))
        s.setThresholds()
        assertEquals(listOf(0x05, 0xDC, 0x09, 0xC4, 0x32), last(0x10))
        assertThrows(IllegalArgumentException::class.java) { s.setThresholds(2000, 1000, 50) }
        assertEquals(0x847B, s.getBaseline())
        s.setBaseline(0x1234)
        assertEquals(listOf(0x12, 0x34), last(0x11))
        assertEquals(0x1234, s.getBaseline())
        assertThrows(IllegalArgumentException::class.java) { s.setBaseline(0x10000) }
    }

    @Test
    fun ntcStatusAndVersions() {
        val s = make()
        assertEquals(Pair(1600, 800), s.readNtc())
        assertEquals(50000.0, s.ntcResistance(100000.0), 1e-9)
        c.mail[0x06] = intArrayOf(0x00, 0x00, 0x03, 0x20)
        assertThrows(IOException::class.java) { s.ntcResistance(100000.0) }
        c.dataReady = false
        assertEquals(0x90, s.status())
        c.mail[0xE0] = intArrayOf(0x10)
        assertEquals(0x10, s.error())
        assertEquals(Pair(0x81, 0x12), s.hardwareVersion())
        val fw = s.firmwareVersion()
        assertEquals(Ccs811Full.Version(1, 0, 0), fw.boot)
        assertEquals(Ccs811Full.Version(1, 1, 2), fw.app)
    }

    @Test
    fun interruptAndErrors() {
        val s = make()
        assertEquals(0x98, s.pollInterrupt())
        assertTrue(c.dataReady)
        val pin = FakePin()
        val seen = mutableListOf<Int>()
        s.onInterrupt(IntConsumer { seen.add(it) }, pin)
        assertEquals(EdgeTrigger.FALLING, pin.trigger)
        pin.handler!!.onEdge()
        assertEquals(listOf(0x98), seen)
        c.dataReady = false
        pin.handler!!.onEdge()
        assertEquals(1, seen.size)
        s.offInterrupt()
        assertNull(pin.handler)

        c.dataReady = true
        c.mail[0x02] = intArrayOf(0x01, 0xF4, 0x00, 0x0F, 0x99, 0x08)
        c.mail[0xE0] = intArrayOf(0x08)
        val e = assertThrows(IOException::class.java) { s.read() }
        assertTrue(e.message!!.contains("0x08"))
    }

    @Test
    fun softResetRestartsApplication() {
        val s = make()
        val n = c.writes().size
        s.setMode(3)
        s.softReset()
        assertTrue(wrote(c, n, 0xFF, 0x11, 0xE5, 0x72, 0x8A))
        assertTrue(wrote(c, n, 0xF4))
        assertEquals(listOf(0x10), last(0x01))
        c.dataReady = true
        assertEquals(500, s.read().eco2Ppm)
    }

    @Test
    fun minimalAndInitErrors() {
        val m = Mock().apply { fwMode = true }
        val mn = Ccs811Minimal(m)
        mn.init()
        assertFalse(wrote(m, 0, 0xF4))
        assertEquals(Ccs811Minimal.Measurement(500, 15), mn.read())
        val bad = Mock().apply { mail[0x20] = intArrayOf(0x55) }
        assertThrows(IOException::class.java) { Ccs811Minimal(bad).init() }
        val noApp = Mock().apply { appValid = false }
        assertThrows(IOException::class.java) { Ccs811Minimal(noApp).init() }
        val stuck = Mock().apply { readyAfterStart = false }
        assertThrows(IOException::class.java) { Ccs811Minimal(stuck).init() }
    }
}
