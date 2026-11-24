package it.uhde.periph.chips.light

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class Si1132Test {

    /** Models the Si1132 sequencer: RESPONSE counter, parameter RAM, forced measurement. */
    class ChipMock(seqId: Int = 0x08, partId: Int = 0x32) : MockConnection() {
        val params = HashMap<Int, Int>()
        val cmds = ArrayList<Int>()
        var vis = 1234
        var ir = 567
        var aux = 250
        var stuck = false
        var cmdError = -1
        var pin: InputPin? = null

        init {
            registers()[0x00] = partId
            registers()[0x02] = seqId
        }

        fun r(a: Int): Int = registers()[a] ?: 0

        private fun count() { registers()[0x20] = (r(0x20) + 1) and 0x0F }

        override fun write(data: ByteArray) {
            super.write(data)
            if (data.size != 2) return
            val reg = data[0].toInt() and 0xFF
            val v = data[1].toInt() and 0xFF
            if (reg == 0x21) {
                registers()[0x21] = r(0x21) and v.inv() and 0xFF
            } else if (reg == 0x18) {
                cmds.add(v)
                command(v)
            }
        }

        private fun command(cmd: Int) {
            if (cmdError >= 0 && cmd != 0x00) { registers()[0x20] = cmdError; return }
            when {
                (cmd and 0xE0) == 0xA0 -> {
                    params[cmd and 0x1F] = r(0x17)
                    registers()[0x2E] = params[cmd and 0x1F]!!
                    count()
                }
                (cmd and 0xE0) == 0x80 -> { registers()[0x2E] = params[cmd and 0x1F] ?: 0; count() }
                cmd == 0x00 -> registers()[0x20] = 0
                cmd == 0x06 -> { if (!stuck) measure(); count() }
                cmd == 0x12 -> { for (i in 0 until 12) registers()[0x22 + i] = 0x10 + i; count() }
                cmd != 0x01 -> count()
            }
        }

        private fun measure() {
            var a = aux
            if ((params[0x01] ?: 0) == 0x40) a = if ((params[0x0F] ?: 0) == 0x65) 0x4000 else 0x2000
            setRegister(0x22, vis and 0xFF, vis shr 8, ir and 0xFF, ir shr 8)
            setRegister(0x2C, a and 0xFF, a shr 8)
            registers()[0x21] = r(0x21) or 0x01
        }

        override fun intPin(): InputPin? = pin
    }

    class FakePin : InputPin {
        var handler: EdgeHandler? = null
        var trigger: EdgeTrigger? = null
        override fun onEdge(handler: EdgeHandler, trigger: EdgeTrigger) {
            this.handler = handler
            this.trigger = trigger
        }
        override fun offEdge(handler: EdgeHandler) {
            if (this.handler === handler) this.handler = null
        }
        override fun isHigh(): Boolean = false
        override fun close() {}
    }

    private fun wrote(c: ChipMock, vararg bytes: Int): Boolean = c.writes().any { w ->
        w.size == bytes.size && w.indices.all { (w[it].toInt() and 0xFF) == bytes[it] }
    }

    @Test
    fun initConfiguresChip() {
        val c = ChipMock()
        Si1132Minimal(c)
        assertTrue(wrote(c, 0x07, 0x17))
        assertTrue(wrote(c, 0x13, 0x7B, 0x6B, 0x01, 0x00))
        assertEquals(0xB0, c.params[0x01])
        assertEquals(0x01, c.r(0x04))
    }

    @Test
    fun wrongPartIdThrows() {
        assertThrows(IOException::class.java) { Si1132Minimal(ChipMock(0x08, 0x45)) }
    }

    @Test
    fun readReturnsUvAndCounts() {
        val c = ChipMock()
        val m = Si1132Minimal(c).read()
        assertEquals(2.5, m[0], 1e-6)
        assertEquals(1234.0, m[1], 0.0)
        assertEquals(567.0, m[2], 0.0)
        assertTrue(c.cmds.contains(0x06))
        assertEquals(0, c.r(0x21))
    }

    @Test
    fun measurementTimeout() {
        val c = ChipMock()
        c.stuck = true
        val s = Si1132Minimal(c)
        assertThrows(IOException::class.java) { s.read() }
    }

    @Test
    fun commandErrorSendsNop() {
        val c = ChipMock()
        val f = Si1132Full(c)
        c.cmdError = 0x80
        assertThrows(IOException::class.java) { f.setGain(1, 1) }
        assertEquals(0x00, c.cmds.last())
    }

    @Test
    fun singleChannelsAndAux() {
        val c = ChipMock()
        val f = Si1132Full(c)
        assertEquals(2.5, f.readUvIndex(), 1e-6)
        assertEquals(1234, f.readVisible())
        assertEquals(567, f.readInfrared())
        assertArrayEquals(intArrayOf(1234, 567, 250), f.readRaw())
        assertEquals(0x4000, f.readTemperatureRaw())
        assertEquals(0x65, c.params[0x0F])
        assertEquals(0xB0, c.params[0x01])
        assertEquals(0x2000, f.readVddRaw())
        assertEquals(0x75, c.params[0x0F])
    }

    @Test
    fun configuration() {
        val c = ChipMock()
        val f = Si1132Full(c)
        f.setChannels(true, false, true)
        assertEquals(0xA0, c.params[0x01])
        f.setChannels(false, true, false, true)
        assertEquals(0x50, c.params[0x01])
        assertThrows(IllegalArgumentException::class.java) { f.setChannels(false, false, false) }
        assertThrows(IllegalArgumentException::class.java) { f.setChannels(true, true, true, true) }
        f.setGain(3, 5)
        assertEquals(3, c.params[0x11])
        assertEquals(0x40, c.params[0x10])
        assertEquals(5, c.params[0x1E])
        assertEquals(0x20, c.params[0x1D])
        assertThrows(IllegalArgumentException::class.java) { f.setGain(8, 0) }
        f.setHighRange(true, false)
        assertEquals(0x20, c.params[0x12])
        assertEquals(0x00, c.params[0x1F])
        f.setAlignment(true, true)
        assertEquals(0x30, c.params[0x06])
        f.setUvCoefficients(intArrayOf(1, 2, 3, 4))
        assertTrue(wrote(c, 0x13, 1, 2, 3, 4))
        assertThrows(IllegalArgumentException::class.java) { f.setUvCoefficients(intArrayOf(1, 2)) }
    }

    @Test
    fun calibration() {
        val cal = Si1132Full(ChipMock()).readCalibration()
        assertEquals(12, cal.size)
        assertEquals(0x10, cal[0])
        assertEquals(0x1B, cal[11])
    }

    @Test
    fun autonomous() {
        val c = ChipMock()
        val f = Si1132Full(c)
        f.startAutonomous(0.5)
        assertEquals(0x80, c.r(0x08))
        assertEquals(0x3E, c.r(0x09))
        assertEquals(0x0E, c.cmds.last())
        f.startAutonomous(0.0)
        assertEquals(0x0A, c.cmds.last())
        assertThrows(IllegalArgumentException::class.java) { f.startAutonomous(3.0) }
        assertThrows(IllegalArgumentException::class.java) { f.startAutonomous(-1.0) }
        val c1 = ChipMock(0x01, 0x32)
        Si1132Full(c1).startAutonomous(0.5)
        assertEquals(0x80, c1.r(0x0A))
        assertEquals(0x3E, c1.r(0x08))
    }

    @Test
    fun statusAndControl() {
        val c = ChipMock()
        val f = Si1132Full(c)
        f.pause()
        assertEquals(0x0A, c.cmds.last())
        f.force()
        assertEquals(0x06, c.cmds.last())
        assertTrue(f.measurementReady())
        c.registers()[0x21] = 0
        assertFalse(f.measurementReady())
        c.registers()[0x20] = 0x05
        assertArrayEquals(intArrayOf(5, 0), f.readResponse())
        c.registers()[0x20] = 0x8C
        assertArrayEquals(intArrayOf(0, 0x8C), f.readResponse())
        f.setI2cAddress(0x61)
        assertEquals(0x61, c.params[0x00])
        assertEquals(0x02, c.cmds.last())
        f.reset()
        assertTrue(c.cmds.contains(0x01))
        assertTrue(wrote(c, 0x07, 0x17))
    }

    @Test
    fun interruptRegisters() {
        val c = ChipMock()
        val f = Si1132Full(c)
        f.enableInterrupt()
        assertEquals(0x01, c.r(0x03))
        assertEquals(0x01, c.r(0x04))
        f.disableInterrupt()
        assertEquals(0x00, c.r(0x03))
        c.registers()[0x21] = 0x01
        assertEquals(0x01, f.pollInterrupt())
        assertEquals(0, c.r(0x21))
        assertEquals(0, f.pollInterrupt())
    }

    @Test
    fun interruptViaPin() {
        val c = ChipMock()
        val pin = FakePin()
        c.pin = pin
        val f = Si1132Full(c)
        val got = AtomicInteger(-1)
        f.onInterrupt { got.set(it) }
        assertEquals(0x01, c.r(0x03))
        assertEquals(EdgeTrigger.FALLING, pin.trigger)
        c.registers()[0x21] = 0x01
        pin.handler!!.onEdge()
        assertEquals(0x01, got.get())
        f.offInterrupt()
        assertNull(pin.handler)
        assertEquals(0x00, c.r(0x03))
    }

    @Test
    fun interruptPollingFallback() {
        val c = ChipMock()
        val f = Si1132Full(c)
        val got = AtomicInteger(-1)
        f.onInterrupt { got.set(it) }
        c.registers()[0x21] = 0x01
        val end = System.currentTimeMillis() + 1000
        while (got.get() < 0 && System.currentTimeMillis() < end) Thread.sleep(5)
        f.offInterrupt()
        assertEquals(0x01, got.get())
    }
}
