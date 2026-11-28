package it.uhde.periph.chips.light

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

class TSL2561Test {

    companion object {
        @BeforeAll
        @JvmStatic
        fun noWait() {
            TSL2561Minimal.waitPercent = 0   // skip the integration waits
        }

        val T = arrayOf(doubleArrayOf(0.50, 0.0304, 0.062), doubleArrayOf(0.61, 0.0224, 0.031),
            doubleArrayOf(0.80, 0.0128, 0.0153), doubleArrayOf(1.30, 0.00146, 0.00112))
        val CS = arrayOf(doubleArrayOf(0.52, 0.0315, 0.0593), doubleArrayOf(0.65, 0.0229, 0.0291),
            doubleArrayOf(0.80, 0.0157, 0.0180), doubleArrayOf(1.30, 0.00338, 0.00260))

        fun ref(ch0: Double, ch1: Double, gain: Double, scale: Double, k: Array<DoubleArray>): Double {
            val c0 = ch0 * (16 / gain) / scale
            val c1 = ch1 * (16 / gain) / scale
            val r = c1 / c0
            if (r <= k[0][0]) return k[0][1] * c0 - k[0][2] * c0 * r.pow(1.4)
            for (i in 1..3) if (r <= k[i][0]) return k[i][1] * c0 - k[i][2] * c1
            return 0.0
        }

        fun counts(c: MockConnection, ch0: Int, ch1: Int) =
            c.setRegister(0x9C, ch0 and 0xFF, ch0 shr 8, ch1 and 0xFF, ch1 shr 8)
    }

    private fun mock(): MockConnection = MockConnection().also { it.setRegister(0x8A, 0x15) }

    private fun reg(c: MockConnection, r: Int): Int = c.registers()[r] ?: 0

    private fun wrote(c: MockConnection, vararg bytes: Int): Boolean {
        val want = ByteArray(bytes.size) { bytes[it].toByte() }
        return c.writes().any { it.contentEquals(want) }
    }

    /** Counts follow the TIMING register: the light level is fixed, counts scale with gain and time. */
    class RangeMock(private val base: Double) : MockConnection() {
        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            if (data[0].toInt() and 0xFF == 0x9C) {
                val t = registers()[0x81] ?: 0x02
                val gain = if (t and 0x10 != 0) 16.0 else 1.0
                val scale = doubleArrayOf(0.034, 0.252, 1.0)[t and 3]
                val limit = doubleArrayOf(5047.0, 37177.0, 65535.0)[t and 3]
                counts(this, min(limit, floor(base * gain * scale)).toInt(), min(limit, floor(base * 0.2 * gain * scale)).toInt())
            }
            return super.writeRead(data, n)
        }
    }

    class FakePin : InputPin {
        var handler: EdgeHandler? = null
        override fun onEdge(h: EdgeHandler, t: EdgeTrigger) { handler = h }
        override fun offEdge(h: EdgeHandler) { if (handler === h) handler = null }
        override fun isHigh(): Boolean = false
        override fun close() {}
    }

    @Test
    fun initSequence() {
        val c = mock()
        TSL2561Full(c)
        val data = c.writes().filter { it.size > 1 }.map { it.map { b -> b.toInt() and 0xFF } }
        assertEquals(listOf(listOf(0x80, 0x03), listOf(0x81, 0x02)), data)
        assertTrue(wrote(c, 0x80))
    }

    @Test
    fun initRejectsBadControl() {
        val dead = object : MockConnection() {
            override fun write(data: ByteArray) {
                super.write(data)
                setRegister(0x80, 0x00)
            }
        }
        assertThrows(IOException::class.java) { TSL2561Minimal(dead) }
    }

    @Test
    fun luxFormula() {
        val c = mock()
        val chip = TSL2561Full(c)
        counts(c, 1000, 200)
        val lx = chip.readLux()
        assertEquals(ref(1000.0, 200.0, 1.0, 1.0, T), lx, 1e-9)
        assertEquals(382.2, lx, 0.5)
        for (c1 in listOf(550, 700, 1000)) {
            counts(c, 1000, c1)
            assertEquals(ref(1000.0, c1.toDouble(), 1.0, 1.0, T), chip.readLux(), 1e-9)
        }
        counts(c, 1000, 1400)
        assertEquals(0.0, chip.readLux(), 0.0)
        counts(c, 0, 0)
        assertEquals(0.0, chip.readLux(), 0.0)
        assertEquals(ref(500.0, 100.0, 16.0, 0.252, T), TSL2561Minimal.calculateLux(500, 100, 16, 101, "T"), 1e-9)
        assertEquals(ref(500.0, 300.0, 1.0, 0.034, T), TSL2561Minimal.calculateLux(500, 300, 1, 14, "FN"), 1e-9)
        for (c1 in listOf(100, 600, 700, 1000)) {
            assertEquals(ref(1000.0, c1.toDouble(), 1.0, 1.0, CS), TSL2561Minimal.calculateLux(1000, c1, 1, 402, "CS"), 1e-9)
        }
    }

    @Test
    fun rawAndSaturation() {
        val c = mock()
        val chip = TSL2561Full(c)
        counts(c, 0x1234, 0x0567)
        assertEquals(Pair(0x1234, 0x0567), chip.readRaw())
        assertArrayEquals(byteArrayOf(0x9C.toByte()), c.writes().last())
        val r = chip.readLuxRaw()
        assertEquals(0x1234, r.ch0)
        assertEquals(ref(4660.0, 1383.0, 1.0, 1.0, T), r.lux, 1e-9)
        counts(c, 65535, 100)
        val e = assertThrows(TSL2561Minimal.SaturatedException::class.java) { chip.readLux() }
        assertEquals(65535, e.ch0)
        chip.setIntegrationTime(101)
        counts(c, 37177, 10)
        assertThrows(TSL2561Minimal.SaturatedException::class.java) { chip.readLux() }
        counts(c, 37176, 10)
        assertTrue(chip.readLux() > 0)
    }

    @Test
    fun configuration() {
        val c = mock()
        val chip = TSL2561Full(c)
        chip.setGain(16)
        assertEquals(0x12, reg(c, 0x81))
        assertEquals(16, chip.getGain())
        chip.setIntegrationTime(14)
        assertEquals(0x10, reg(c, 0x81))
        assertEquals(14, chip.getIntegrationTime())
        chip.setGain(1)
        chip.setIntegrationTime(101)
        assertEquals(0x01, reg(c, 0x81))
        assertThrows(IllegalArgumentException::class.java) { chip.setGain(4) }
        assertThrows(IllegalArgumentException::class.java) { chip.setIntegrationTime(50) }
        assertThrows(IllegalArgumentException::class.java) { chip.setPackage("X") }
        chip.setIntegrationTime(402)
        counts(c, 1000, 100)
        chip.setPackage("CS")
        assertEquals(ref(1000.0, 100.0, 1.0, 1.0, CS), chip.readLux(), 1e-9)
        chip.setGain(16)
        chip.startManual()
        assertEquals(0x1B, reg(c, 0x81))
        chip.stopManual()
        assertEquals(0x13, reg(c, 0x81))
        assertEquals(0, chip.getIntegrationTime())
    }

    @Test
    fun autoRange() {
        val dim = TSL2561Full(RangeMock(20.0))
        assertTrue(dim.autoRange() > 0)
        assertEquals(16, dim.getGain())
        assertEquals(402, dim.getIntegrationTime())
        val bright = TSL2561Full(RangeMock(148000.0))
        assertTrue(bright.autoRange() > 0)
        assertEquals(1, bright.getGain())
        assertEquals(14, bright.getIntegrationTime())
        val mid = TSL2561Full(RangeMock(5000.0))
        val lx = mid.autoRange()
        assertEquals(402, mid.getIntegrationTime())
        assertEquals(TSL2561Minimal.calculateLux(5000, 1000), lx, 1e-9)
    }

    @Test
    fun powerThresholdsInterrupt() {
        val c = mock()
        val chip = TSL2561Full(c)
        assertTrue(chip.isPowered())
        chip.powerDown()
        assertFalse(chip.isPowered())
        chip.powerUp()
        assertEquals(0x03, reg(c, 0x80))
        chip.setThresholds(0x0102, 0x0A0B)
        assertTrue(wrote(c, 0xA2, 0x02, 0x01) && wrote(c, 0xA4, 0x0B, 0x0A))
        assertEquals(Pair(0x0102, 0x0A0B), chip.getThresholds())
        assertThrows(IllegalArgumentException::class.java) { chip.setThresholds(-1, 70000) }
        chip.configureInterrupt(1, 2)
        assertEquals(0x12, reg(c, 0x86))
        assertThrows(IllegalArgumentException::class.java) { chip.configureInterrupt(3, 0) }
        assertThrows(IllegalArgumentException::class.java) { chip.configureInterrupt(1, 16) }
        assertEquals(1, chip.readId() shr 4)
        chip.setThresholds(100, 1000)
        counts(c, 500, 0)
        assertFalse(chip.pollInterrupt())
        counts(c, 100, 0)
        assertTrue(chip.pollInterrupt())
        counts(c, 1001, 0)
        assertTrue(chip.pollInterrupt())
        chip.clearInterrupt()
        assertArrayEquals(byteArrayOf(0xC0.toByte()), c.writes().last())

        val pin = FakePin()
        val seen = mutableListOf<Int>()
        chip.onInterrupt(pin) { seen.add(it) }
        counts(c, 4242, 0)
        pin.handler!!.onEdge()
        assertEquals(listOf(4242), seen)
        assertArrayEquals(byteArrayOf(0xC0.toByte()), c.writes().last())
        chip.offInterrupt()
        assertNull(pin.handler)
    }
}
