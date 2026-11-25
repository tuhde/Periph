package it.uhde.periph.chips.color

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class As7262Test {

    /** AS7262 simulator: STATUS/WRITE/READ hardware registers over virtual registers. */
    class Sim : MockConnection() {
        val v = IntArray(0x2C)
        var pending = -1
        var out = -1
        var stall = false
        val vwrites = ArrayList<IntArray>()

        init {
            v[0] = 0x40; v[1] = 0x3E; v[2] = 0x41; v[3] = 0x21; v[4] = 0x08; v[5] = 0xFF; v[6] = 25
        }

        override fun read(reg: Int, length: Int): ByteArray {
            if (reg == 0x00) return byteArrayOf((if (stall) 0x02 else if (out >= 0) 0x01 else 0x00).toByte())
            if (reg == 0x02) {
                val b = (if (out >= 0) out else 0).toByte()
                out = -1
                return byteArrayOf(b)
            }
            return ByteArray(length)
        }

        override fun write(reg: Int, data: ByteArray) {
            val b = data[0].toInt() and 0xFF
            if (pending >= 0) {
                val r = pending
                pending = -1
                vwrites.add(intArrayOf(r, b))
                if (r == 4) {
                    if ((b and 0x80) != 0) { v[4] = 0x08; v[5] = 0xFF; v[7] = 0; return }
                    v[4] = b or (v[4] and 0x02)
                    if (((b shr 2) and 3) == 3) v[4] = v[4] or 0x02
                } else {
                    v[r] = b
                }
            } else if ((b and 0x80) != 0) {
                pending = b and 0x7F
            } else {
                out = v[b]
                if (b >= 0x08) v[4] = v[4] and 0x02.inv()
            }
        }

        fun wrote(r: Int, value: Int) = vwrites.any { it[0] == r && it[1] == value }

        fun loadCal(vararg vals: Float) {
            for (i in vals.indices) {
                val b = ByteBuffer.allocate(4).putFloat(vals[i]).array()
                for (k in 0 until 4) v[0x14 + 4 * i + k] = b[k].toInt() and 0xFF
            }
        }
    }

    private fun ready(c: Sim): As7262Full = As7262Full(c).also { it.init() }

    @Test
    fun constructorMakesNoBusAccess() {
        val c = Sim()
        As7262Full(c)
        assertTrue(c.vwrites.isEmpty())
    }

    @Test
    fun initWritesDefaults() {
        val c = Sim()
        As7262Minimal(c).init()
        assertEquals(0x3B, c.v[5])
        assertEquals(2, (c.v[4] shr 4) and 3)
        assertEquals(0, c.v[7])
        assertTrue(c.wrote(4, 0x80))
    }

    @Test
    fun readReturnsCalibratedChannels() {
        val c = Sim()
        val s = As7262Minimal(c)
        s.init()
        c.loadCal(1.0f, 2.5f, 3.0f, 4.0f, 5.0f, 6.5f)
        val ch = s.read()
        assertEquals(1.0f, ch.violet, 1e-4f)
        assertEquals(2.5f, ch.blue, 1e-4f)
        assertEquals(6.5f, ch.red, 1e-4f)
        assertTrue(c.wrote(4, 0x2C))
        assertEquals(25.0, s.temperature(), 1e-9)
    }

    @Test
    fun readRawAndChannels() {
        val c = Sim()
        val s = ready(c)
        for (i in 0 until 12) c.v[0x08 + i] = if (i % 2 == 0) 0x01 else 0x02
        assertEquals(0x0102, s.readRaw().violet)
        assertEquals(0x0102, s.readChannelRaw(5))
        c.loadCal(0f, 0f, 0f, 0f, 0f, 7.25f)
        assertEquals(7.25f, s.readChannel(5), 1e-4f)
        assertThrows(IllegalArgumentException::class.java) { s.readChannel(6) }
    }

    @Test
    fun gainIntegrationAndMode() {
        val c = Sim()
        val s = ready(c)
        s.setGain(64.0)
        assertEquals(64.0, s.getGain())
        s.setGain(3.7)
        assertEquals(3.7, s.getGain())
        s.setGain(1.0)
        assertEquals(1.0, s.getGain())
        assertThrows(IllegalArgumentException::class.java) { s.setGain(5.0) }
        s.setIntegrationTime(50.0)
        assertEquals(18, c.v[5])
        assertEquals(50.4, s.getIntegrationTime(), 1e-6)
        assertThrows(IllegalArgumentException::class.java) { s.setIntegrationTime(1.0) }
        assertThrows(IllegalArgumentException::class.java) { s.setIntegrationTime(800.0) }
        s.setMode(2)
        assertEquals(2, s.getMode())
        s.setContinuous(0)
        assertEquals(0, s.getMode())
        assertThrows(IllegalArgumentException::class.java) { s.setContinuous(3) }
        assertThrows(IllegalArgumentException::class.java) { s.setMode(4) }
        s.setInterrupt(true)
        assertTrue((c.v[4] and 0x40) != 0)
        s.setInterrupt(false)
        assertEquals(0, c.v[4] and 0x40)
    }

    @Test
    fun ledsKeepEachOther() {
        val c = Sim()
        val s = ready(c)
        s.setLed(true, 25.0)
        assertEquals(0x18, c.v[7])
        s.setIndicatorLed(true, 4.0)
        assertEquals(0x18 or 0x05, c.v[7])
        s.setLed(false, 100.0)
        assertEquals(0x30 or 0x05, c.v[7])
        assertThrows(IllegalArgumentException::class.java) { s.setLed(true, 200.0) }
        assertThrows(IllegalArgumentException::class.java) { s.setIndicatorLed(true, 9.0) }
    }

    @Test
    fun versionsAndVirtualAccess() {
        val c = Sim()
        val s = ready(c)
        assertArrayEquals(intArrayOf(0x40, 0x3E), s.hardwareVersion())
        assertEquals(0x40, s.deviceType())
        assertArrayEquals(intArrayOf(2, ((0x21 and 0x0F) shl 2) or 1, 1), s.firmwareVersion())
        s.writeVirtual(0x05, 0x10)
        assertEquals(0x10, s.readVirtual(0x05))
        s.softReset()
        assertEquals(0xFF, c.v[5])
    }

    @Test
    fun measureDataReadyAndPoll() {
        val c = Sim()
        val s = ready(c)
        s.measure()
        assertTrue(s.dataReady())
        assertEquals(1, s.pollInterrupt())
    }

    @Test
    fun interruptPollingFallbackDelivers() {
        val c = Sim()
        val s = ready(c)
        s.setIntegrationTime(10.0)
        s.measure()
        val got = AtomicInteger()
        s.onInterrupt({ got.set(it) }, null)
        val end = System.currentTimeMillis() + 2000
        while (got.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        s.offInterrupt()
        assertEquals(1, got.get())
        assertEquals(0, c.v[4] and 0x40)
    }

    @Test
    fun handshakeTimesOut() {
        val c = Sim()
        val s = ready(c)
        c.stall = true
        assertThrows(IOException::class.java) { s.readVirtual(0) }
    }
}
