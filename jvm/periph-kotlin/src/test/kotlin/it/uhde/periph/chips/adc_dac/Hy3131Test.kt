package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Hy3131Test {

    /** Models the HY3131 framing: (addr<<1)|R/W command, one blank clock on reads, auto-increment. */
    class Chip : Connection {
        val mem = HashMap<Int, Int>()
        val log = ArrayList<ByteArray>()
        var intf = 0x07

        fun set(reg: Int, vararg vals: Int) {
            vals.forEachIndexed { i, v -> mem[reg + i] = v and 0xFF }
        }

        fun get(reg: Int): Int = mem[reg] ?: 0

        override fun write(data: ByteArray) {
            log.add(data.clone())
            val addr = (data[0].toInt() and 0xFF) shr 1
            for (i in 1 until data.size) mem[addr + i - 1] = data[i].toInt() and 0xFF
        }

        override fun read(n: Int): ByteArray = ByteArray(n)

        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            val addr = (data[0].toInt() and 0xFF) shr 1
            val vals = IntArray(n) { i ->
                val a = addr + i
                if (a == 0x1E) { val x = intf; intf = 0; x } else get(a)
            }
            return ByteArray(n) { i ->
                (if (i == 0) vals[0] shr 1 else ((vals[i - 1] and 1) shl 7) or (vals[i] shr 1)).toByte()
            }
        }

        override fun enable() {}
        override fun disable() {}
        override fun isEnabled() = true
        override fun intPin(): InputPin? = null
        override fun enPin(): OutputPin? = null
        override fun close() {}
    }

    @Test
    fun initDefaults() {
        val c = Chip()
        Hy3131Minimal(c, 1.2f)
        assertArrayEquals(byteArrayOf((0x37 shl 1).toByte(), 0x60), c.log[0])
        assertEquals(0x28, c.get(0x33))
        assertEquals(0xC0, c.get(0x31))
        assertEquals(0x00, c.get(0x30))
        assertEquals(0xA3, c.get(0x24))
        assertEquals(0x11, c.get(0x28))
        assertEquals(0x07, c.get(0x22))
        assertEquals(0x80, c.get(0x23))
    }

    @Test
    fun registerFraming() {
        val c = Chip()
        val adc = Hy3131Minimal(c, 1.2f)
        c.set(0x70, 0xA5, 0x5A)
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A), adc.readRegister(0x70, 2))
        adc.writeRegister(0x60, byteArrayOf(1, 2, 3))
        assertEquals(1, c.get(0x60))
        assertEquals(2, c.get(0x61))
        assertEquals(3, c.get(0x62))
    }

    @Test
    fun readRawAndVoltage() {
        val c = Chip()
        val adc = Hy3131Minimal(c, 1.2f)
        c.set(0x00, 0, 0, 0x40)
        c.intf = 0x04
        assertEquals(0x400000, adc.readRaw())
        c.intf = 0x04
        assertEquals(0.5 * 1.2 / 0.9, adc.readVoltage().toDouble(), 1e-5)
        c.set(0x00, 0, 0, 0x80)
        c.intf = 0x04
        assertEquals(-8388608, adc.readRaw())
        c.intf = 0x04
        assertTrue(adc.dataReady())
        c.set(0x00, 1, 0, 0)
        assertEquals(1, adc.readRaw())
    }

    @Test
    fun fullFeatures() {
        val c = Chip()
        val f = Hy3131Full(c, 1.2f)
        f.configureAd1(4, 2, 3, 1, 1, 3)
        assertEquals((1 shl 5) or (2 shl 3) or 4, c.get(0x22))
        assertEquals(0x93, c.get(0x23))
        assertEquals(3, (c.get(0x25) shr 4) and 3)
        c.set(0x00, 0, 0, 0x40)
        c.intf = 0x04
        assertEquals((0.5 * 0.3333333333 - 0.25) * 1.2 / 3.6, f.readVoltage().toDouble(), 1e-5)

        f.setAd1Input(0x8, 0x5, 1)
        f.setAd1Reference(2, 6)
        assertEquals(0x85, c.get(0x24))
        assertEquals(1, (c.get(0x33) shr 2) and 3)
        assertEquals(0x26, c.get(0x28))

        f.enableAd2(3, 2, 0, false, false, 1, true)
        assertEquals(0x83, c.get(0x26))
        assertEquals(0x84, c.get(0x34) and 0x84)
        assertEquals(2, (c.get(0x25) shr 6) and 3)
        c.set(0x03, 0xFF, 0xFF, 0xFF)
        c.intf = 0x02
        assertEquals(-1, f.readAd2Raw())

        f.enableLpf(3)
        f.enableRms()
        assertEquals(0xC0 or (3 shl 3), c.get(0x29))
        c.set(0x09, 0x00, 0x90, 0x01, 0x00, 0x00)
        c.intf = 0x10
        assertEquals(320.0, f.readRms().toDouble(), 1e-3)

        f.enablePeakHold(2)
        assertEquals(0x06, c.get(0x29) and 0x07)
        c.set(0x0E, 0x00, 0x00, 0xFE, 0x00, 0x00, 0x01)
        assertArrayEquals(intArrayOf(-131072, 65536), f.readPeak())

        f.startCounter(0x1234)
        assertEquals(0x34, c.get(0x1C))
        assertEquals(0x12, c.get(0x1D))
        assertEquals(0x0A, c.get(0x20) and 0x0A)
        c.set(0x14, 0x01)
        c.intf = 0x01
        assertEquals(1L, f.readCounter()[3])

        c.intf = 0x14
        assertEquals(0x14, f.readFlags())
        f.enableInterrupts(0xFF)
        assertEquals(0x1F, c.get(0x1F))
    }
}
