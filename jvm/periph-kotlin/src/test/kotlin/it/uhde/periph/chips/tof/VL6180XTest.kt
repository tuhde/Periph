package it.uhde.periph.chips.tof

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class VL6180XTest {

    /**
     * VL6180X simulator on top of [MockConnection] with explicit 16-bit register indices: starting
     * a measurement makes a result pending, SYSTEM__INTERRUPT_CLEAR drops it.
     */
    class Sim(fresh: Int) : MockConnection() {
        val regs = ConcurrentHashMap<Int, Int>()
        val log = CopyOnWriteArrayList<List<Int>>()

        init {
            setAddressWidth(2)
            set(0x000, 0xB4, 0x01, 0x03)
            set(0x016, fresh)
            set(0x062, 42)
            set(0x050, 0x01, 0x90)   // ALS 400 counts
        }

        fun set(reg: Int, vararg values: Int) = values.forEachIndexed { i, v -> regs[reg + i] = v }
        fun reg(r: Int): Int = regs.getOrDefault(r, 0)
        fun writesTo(r: Int): List<Int> = log.filter { it.size == 3 && ((it[0] shl 8) or it[1]) == r }.map { it[2] }
        fun last(r: Int): Int = writesTo(r).lastOrNull() ?: -1

        @Synchronized
        override fun write(data: ByteArray) {
            val w = data.map { it.toInt() and 0xFF }
            log += w
            val reg = (w[0] shl 8) or w[1]
            for (i in 2 until w.size) regs[reg + i - 2] = w[i]
            if (w.size == 3) {
                if (reg == 0x018 && (w[2] and 1) != 0) regs[0x04F] = (reg(0x04F) and 0x07.inv()) or 4
                else if (reg == 0x038 && (w[2] and 1) != 0) regs[0x04F] = (reg(0x04F) and 0x38.inv()) or (4 shl 3)
                else if (reg == 0x015) regs[0x04F] = 0
                else if (reg == 0x02E) regs[0x02E] = 0
            }
        }

        @Synchronized
        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            val reg = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            return ByteArray(n) { i -> reg(reg + i).toByte() }
        }
    }

    /** InputPin whose single handler the test triggers by hand. */
    class FakePin : InputPin {
        var handler: EdgeHandler? = null
        override fun onEdge(handler: EdgeHandler, trigger: EdgeTrigger) { this.handler = handler }
        override fun offEdge(handler: EdgeHandler) { this.handler = null }
        override fun isHigh(): Boolean = false
        override fun close() {}
    }

    @Test
    fun initAppliesPrivateAndRecommendedSettings() {
        val sim = Sim(1)
        VL6180XMinimal(sim)
        assertEquals(listOf(0x01), sim.writesTo(0x0207))
        assertEquals(0xCE, sim.last(0x00DB))
        assertEquals(0, sim.last(0x016))
        assertEquals(0x10, sim.last(0x011))
        assertEquals(0x30, sim.last(0x10A))
        assertEquals(0x46, sim.last(0x03F))
        assertEquals(0xFF, sim.last(0x031))
        assertEquals(0x24, sim.last(0x014))
        assertEquals(0x09, sim.last(0x01B))
        assertEquals(0x31, sim.last(0x03E))
        assertEquals(0x00, sim.reg(0x040))
        assertEquals(0x63, sim.reg(0x041))
    }

    @Test
    fun initSkipsPrivateSettingsWhenNotFresh() {
        val sim = Sim(0)
        VL6180XMinimal(sim)
        assertTrue(sim.writesTo(0x0207).isEmpty())
        assertEquals(0x24, sim.last(0x014))
    }

    @Test
    fun readRange() {
        val sim = Sim(1)
        val chip = VL6180XMinimal(sim)
        assertEquals(42, chip.readRange())
        assertTrue(sim.writesTo(0x018).contains(0x01))
        assertEquals(0x07, sim.last(0x015))
        sim.set(0x04D, 0x70)
        assertThrows(IOException::class.java) { chip.readRange() }
        assertEquals(7, chip.lastError())
    }

    @Test
    fun fullRangeAndAls() {
        val sim = Sim(1)
        val full = VL6180XFull(sim)
        sim.set(0x04D, 0x60)
        assertArrayEquals(intArrayOf(42, 6), full.readRangeStatus())
        sim.set(0x04D, 0x00)

        assertEquals(400, full.readAlsRaw())
        assertEquals(0.32 * 400 / 1.01, full.readLux(), 1e-9)
        full.setAlsGain(0)
        assertEquals(0x40, sim.last(0x03F))
        full.setAlsIntegrationTime(50)
        assertEquals(0x00, sim.reg(0x040))
        assertEquals(0x31, sim.reg(0x041))
        assertEquals(0.32 * (400 / 20.0) * 2, full.readLux(), 1e-9)
        val hold = sim.writesTo(0x017)
        assertEquals(1, hold[hold.size - 2])
        assertEquals(0, hold[hold.size - 1])
        assertThrows(IllegalArgumentException::class.java) { full.setAlsGain(8) }
        assertThrows(IllegalArgumentException::class.java) { full.setAlsIntegrationTime(513) }
        full.setAlsScaler(4)
        assertEquals(4, sim.last(0x120))
        assertThrows(IllegalArgumentException::class.java) { full.setAlsScaler(17) }
    }

    @Test
    fun continuousModes() {
        val sim = Sim(1)
        val full = VL6180XFull(sim)
        full.startContinuousRange(50)
        assertEquals(0x04, sim.last(0x01B))
        assertEquals(0x03, sim.last(0x018))
        sim.regs[0x04F] = 4
        assertEquals(42, full.readRangeContinuous())
        full.stopContinuousRange()
        assertEquals(0x01, sim.last(0x018))
        assertThrows(IllegalArgumentException::class.java) { full.startContinuousRange(5) }
        assertThrows(IllegalArgumentException::class.java) { full.startContinuousAls(2560) }

        full.startContinuousAls(100)
        assertEquals(0x09, sim.last(0x03E))
        assertEquals(0x03, sim.last(0x038))
        full.stopContinuousAls()
        assertEquals(0x01, sim.last(0x038))
        full.setInterleaved(true)
        assertEquals(1, sim.last(0x2A3))
        full.setInterleaved(false)
        assertEquals(0, sim.last(0x2A3))
    }

    @Test
    fun thresholdsAndTuning() {
        val sim = Sim(1)
        val full = VL6180XFull(sim)
        full.setRangeThresholds(30, 80)
        assertEquals(80, sim.last(0x019))
        assertEquals(30, sim.last(0x01A))
        assertThrows(IllegalArgumentException::class.java) { full.setRangeThresholds(90, 80) }
        full.setAlsThresholds(100, 5000)
        assertEquals(0x13, sim.reg(0x03A))
        assertEquals(0x88, sim.reg(0x03B))
        assertEquals(0x00, sim.reg(0x03C))
        assertEquals(100, sim.reg(0x03D))
        full.setMaxConvergenceTime(20)
        assertEquals(20, sim.last(0x01C))
        assertThrows(IllegalArgumentException::class.java) { full.setMaxConvergenceTime(64) }
        full.setAveragingPeriod(100)
        assertEquals(100, sim.last(0x10A))

        full.setRangeOffset(-5)
        assertEquals(0xFB, sim.last(0x024))
        assertEquals(-5, full.getRangeOffset())
        assertThrows(IllegalArgumentException::class.java) { full.setRangeOffset(128) }
        full.setCrosstalkRate(0.5)
        assertEquals(0x00, sim.reg(0x01E))
        assertEquals(0x40, sim.reg(0x01F))
        assertThrows(IllegalArgumentException::class.java) { full.setCrosstalkRate(-1.0) }
        full.recalibrateVhv()
        assertEquals(0x01, sim.last(0x02E))
    }

    @Test
    fun addressIdentificationHistory() {
        val sim = Sim(1)
        val full = VL6180XFull(sim)
        full.setAddress(0x30)
        assertEquals(0x30, sim.last(0x212))
        assertThrows(IllegalArgumentException::class.java) { full.setAddress(0x80) }
        assertEquals(0xB4, full.modelId())
        assertArrayEquals(intArrayOf(1, 3), full.revision())
        sim.set(0x052, 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 1, 0)
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5, 6, 7, 256), full.readHistory())
    }

    @Test
    fun interrupts() {
        val sim = Sim(1)
        val full = VL6180XFull(sim)
        sim.regs[0x014] = 0x24
        full.enableInterrupt(VL6180XMinimal.SOURCE_RANGE, VL6180XMinimal.MODE_OUT_OF_WINDOW)
        assertEquals(0x23, sim.last(0x014))
        full.enableInterrupt(VL6180XMinimal.SOURCE_ALS, VL6180XMinimal.MODE_LEVEL_LOW)
        assertEquals(0x0B, sim.last(0x014))
        full.disableInterrupt(VL6180XMinimal.SOURCE_RANGE)
        assertEquals(0x08, sim.last(0x014))
        assertThrows(IllegalArgumentException::class.java) { full.enableInterrupt(VL6180XMinimal.SOURCE_ERROR, 1) }
        assertThrows(IllegalArgumentException::class.java) { full.enableInterrupt(VL6180XMinimal.SOURCE_RANGE, 5) }

        sim.regs[0x04F] = 0x04
        assertEquals(VL6180XMinimal.SOURCE_RANGE, full.pollInterrupt())
        assertEquals(0, sim.reg(0x04F))
        sim.regs[0x04F] = 0x24
        assertEquals(VL6180XMinimal.SOURCE_RANGE or VL6180XMinimal.SOURCE_ALS, full.pollInterrupt())
        sim.regs[0x04F] = 0x40
        assertEquals(VL6180XMinimal.SOURCE_ERROR, full.pollInterrupt())
        assertEquals(0, full.pollInterrupt())

        val pin = FakePin()
        val got = CopyOnWriteArrayList<Int>()
        full.onInterrupt(java.util.function.IntConsumer { got.add(it) }, pin)
        assertNotNull(pin.handler)
        sim.regs[0x04F] = 0x04
        pin.handler!!.onEdge()
        assertEquals(listOf(VL6180XMinimal.SOURCE_RANGE), got.toList())
        full.offInterrupt()
        assertNull(pin.handler)
    }

    @Test
    fun pollingFallbackDeliversEvents() {
        val sim = Sim(1)
        val full = VL6180XFull(sim)
        sim.regs[0x04F] = 0x04
        val got = CopyOnWriteArrayList<Int>()
        full.onInterrupt(java.util.function.IntConsumer { got.add(it) }, null)
        Thread.sleep(50)
        full.offInterrupt()
        assertTrue(got.contains(VL6180XMinimal.SOURCE_RANGE))
    }
}
