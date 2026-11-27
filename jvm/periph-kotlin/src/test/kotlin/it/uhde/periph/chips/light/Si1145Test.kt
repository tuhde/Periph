package it.uhde.periph.chips.light

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.function.IntConsumer

class Si1145Test {

    /** Simulates the command engine: RESPONSE counter, parameter RAM, forced measurements. */
    private class Sim(part: Int, seq: Int) : MockConnection() {
        val regs = IntArray(0x40)
        val params = IntArray(32)
        val ps = intArrayOf(1500, 1600, 1700)
        val commands = mutableListOf<Int>()
        var ignore = false
        var failParam = -1
        var pin: InputPin? = null

        init {
            regs[0] = part
            regs[2] = seq
            params[0x00] = 0x60
            params[0x02] = 0x21
            params[0x0C] = 0x04
            params[0x0F] = 0x65
        }

        fun last() = commands.last()

        fun put16(r: Int, v: Int) { regs[r] = v and 0xFF; regs[r + 1] = (v shr 8) and 0xFF }

        fun measure(p: Boolean, a: Boolean) {
            val ch = params[1]
            var st = 0
            if (a && ch and 0xF0 != 0) {
                if (ch and 0x10 != 0) put16(0x22, 260)
                if (ch and 0x20 != 0) put16(0x24, 300)
                if (ch and 0x80 != 0) put16(0x2C, 345)
                else if (ch and 0x40 != 0) put16(0x2C, if (params[0x0F] == 0x65) 0x1111 else 0x2222)
                st = st or 0x01
            }
            if (p) for (i in 0 until 3) if (ch and (1 shl i) != 0) { put16(0x26 + 2 * i, ps[i]); st = st or (0x04 shl i) }
            regs[0x21] = regs[0x21] or (st and regs[0x04])
        }

        fun command(cmd: Int) {
            commands += cmd
            if (ignore) return
            val resp = regs[0x20]
            when {
                cmd == 0x00 -> { regs[0x20] = 0; return }
                cmd == 0x01 -> { regs[0x20] = 0; regs[0x07] = 0; return }
                resp and 0x80 != 0 -> return
                cmd and 0xE0 == 0xA0 -> {
                    val off = cmd and 0x1F
                    if (off == failParam) { regs[0x20] = 0x80; return }
                    params[off] = regs[0x17]
                    regs[0x2E] = regs[0x17]
                }
                cmd and 0xE0 == 0x80 -> regs[0x2E] = params[cmd and 0x1F]
                cmd in 0x05..0x07 -> measure(cmd != 0x06, cmd != 0x05)
                cmd == 0x12 -> for (i in 0 until 12) regs[0x22 + i] = 0x10 + i
                cmd in listOf(0x02, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F) -> {}
                else -> { regs[0x20] = 0x80; return }
            }
            regs[0x20] = (resp + 1) and 0x0F
        }

        override fun write(data: ByteArray) {
            super.write(data)
            val reg = data[0].toInt() and 0xFF
            for (i in 1 until data.size) {
                val r = reg + i - 1
                val v = data[i].toInt() and 0xFF
                when (r) {
                    0x18 -> command(v)
                    0x21 -> regs[0x21] = regs[0x21] and v.inv()
                    else -> regs[r] = v
                }
            }
        }

        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            super.writeRead(data, n)
            val reg = data[0].toInt() and 0xFF
            return ByteArray(n) { regs[(reg + it) and 0x3F].toByte() }
        }

        override fun intPin(): InputPin? = pin
    }

    private class FakePin : InputPin {
        var handler: EdgeHandler? = null
        var trigger: EdgeTrigger? = null
        override fun onEdge(h: EdgeHandler, t: EdgeTrigger) { handler = h; trigger = t }
        override fun offEdge(h: EdgeHandler) { if (h === handler) handler = null }
        override fun isHigh() = true
        override fun close() {}
    }

    private lateinit var c: Sim

    private fun make(part: Int = 0x45, seq: Int = 0x08): Si1145Full {
        c = Sim(part, seq)
        val s = Si1145Full(c)
        assertTrue(c.writes().isEmpty())
        s.init()
        return s
    }

    @Test
    fun initAndRead() {
        val s = make()
        assertEquals(0x17, c.regs[0x07])
        assertEquals(listOf(0x7B, 0x6B, 0x01, 0x00), c.regs.slice(0x13 until 0x17))
        assertEquals(0xB1, c.params[1])
        assertEquals(0x03, c.regs[0x0F])
        assertEquals(0x05, c.regs[0x04])
        val r = s.read()
        assertEquals(1500, r.proximity)
        assertEquals(3.45, r.uvIndex, 1e-9)
        assertEquals(260, r.visible)
        assertEquals(300, r.infrared)
        assertThrows(IOException::class.java) { Si1145Minimal(Sim(0x32, 0)).init() }
    }

    @Test
    fun singleReadsAndChannels() {
        val s = make()
        assertEquals(1500, s.readProximity())
        assertEquals(0x05, c.last())
        assertThrows(IllegalArgumentException::class.java) { s.readProximity(2) }
        assertEquals(3.45, s.readUvIndex(), 1e-9)
        assertEquals(260, s.readVisible())
        assertEquals(300, s.readInfrared())
        assertEquals(Si1145Full.Raw(260, 300, 1500, 0, 0, 345), s.readRaw())
        assertEquals(0x1111, s.readTemperatureRaw())
        assertEquals(0xB1, c.params[1])
        assertEquals(0x2222, s.readVddRaw())
        s.setChannels(uv = false, visible = true, infrared = false, ps1 = true)
        assertEquals(0x11, c.params[1])
        assertThrows(IllegalArgumentException::class.java) { s.readUvIndex() }
        assertThrows(IllegalArgumentException::class.java) { s.setChannels(true, false, false, false, aux = true) }
        assertThrows(IllegalArgumentException::class.java) { s.setChannels(false, false, false, true, ps2 = true) }
    }

    @Test
    fun configuration() {
        val s = make()
        s.setLedCurrent(1, 15)
        assertEquals(0x0F, c.regs[0x0F])
        assertThrows(IllegalArgumentException::class.java) { s.setLedCurrent(2, 1) }
        assertThrows(IllegalArgumentException::class.java) { s.setPsLed(1, 2) }
        s.setPsPhotodiode(1, false)
        assertEquals(0, c.params[7])
        s.setGain(3, 2, 1)
        assertEquals(listOf(3, 0x40, 2, 0x50, 1, 0x60),
            listOf(c.params[0x11], c.params[0x10], c.params[0x1E], c.params[0x1D], c.params[0x0B], c.params[0x0A]))
        assertThrows(IllegalArgumentException::class.java) { s.setGain(8) }
        s.setHighRange(true, true, true)
        assertEquals(listOf(0x20, 0x20, 0x24), listOf(c.params[0x12], c.params[0x1F], c.params[0x0C]))
        s.setAlignment(visible = true, ps1 = true)
        assertEquals(0x10, c.params[6])
        assertEquals(0x10, c.params[5])
        s.setUvCoefficients(intArrayOf(1, 2, 3, 4))
        assertEquals(4, c.regs[0x16])
        val cal = s.readCalibration()
        assertEquals(0x10, cal[0])
        assertEquals(0x1B, cal[11])
        val s47 = make(0x47)
        s47.setLedCurrent(2, 5)
        s47.setLedCurrent(3, 9)
        s47.setPsLed(2, 2)
        s47.setPsLed(3, 4)
        assertEquals(listOf(0x53, 0x09, 0x21, 0x04), listOf(c.regs[0x0F], c.regs[0x10], c.params[2], c.params[3]))
        s47.setChannels(false, false, false, true, ps2 = true, ps3 = true)
        assertEquals(1700, s47.readProximity(3))
    }

    @Test
    fun autonomousResponseInterrupts() {
        val s = make()
        s.startAutonomous(0.1)
        assertEquals(listOf(0x80, 0x0C, 0x0F), listOf(c.regs[0x08], c.regs[0x09], c.last()))
        s.startAutonomous(0.01, proximity = false)
        assertEquals(0x0E, c.last())
        assertThrows(IllegalArgumentException::class.java) { s.startAutonomous(3.0) }
        s.pause()
        assertEquals(0x0B, c.last())
        s.force(als = false)
        assertEquals(0x05, c.last())
        c.failParam = 0x0B
        assertThrows(IOException::class.java) { s.setGain(0, 0, 1) }
        assertEquals(0x00, c.last())
        c.failParam = -1
        c.regs[0x20] = 0x8C
        assertEquals(Si1145Full.Response(0, 0x8C), s.readResponse())
        s.setGain()
        assertEquals(0, c.regs[0x20] and 0x80)

        s.enableInterrupt(Si1145Minimal.SOURCE_CMD)
        assertEquals(listOf(0x01, 0x25), listOf(c.regs[0x03], c.regs[0x04]))
        s.disableInterrupt(Si1145Minimal.SOURCE_CMD)
        c.regs[0x21] = 0x05
        assertEquals(0x05, s.pollInterrupt())
        assertEquals(0, s.pollInterrupt())
        val pin = FakePin()
        c.pin = pin
        val got = mutableListOf<Int>()
        s.onInterrupt(IntConsumer { got += it })
        assertEquals(EdgeTrigger.FALLING, pin.trigger)
        c.regs[0x21] = 0x04
        pin.handler!!.onEdge()
        pin.handler!!.onEdge()
        assertEquals(listOf(0x04), got)
        s.offInterrupt()
        assertNull(pin.handler)
        assertEquals(Si1145Full.Id(0x45, 0, 0x08), s.readId())
        s.setI2cAddress(0x30)
        assertEquals(0x30, c.params[0])
        c.regs[0x0F] = 0x0F
        s.reset()
        assertEquals(listOf(0x17, 0xB1, 0x03), listOf(c.regs[0x07], c.params[1], c.regs[0x0F]))

        val sw = make(0x45, 0x01)
        sw.startAutonomous(0.1)
        assertEquals(listOf(0x80, 0x0C), listOf(c.regs[0x0A], c.regs[0x08]))
        c.ignore = true
        assertThrows(IOException::class.java) { sw.read() }
    }
}
