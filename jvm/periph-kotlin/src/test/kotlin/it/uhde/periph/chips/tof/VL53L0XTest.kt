package it.uhde.periph.chips.tof

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

class VL53L0XTest {

    /**
     * Page-aware VL53L0X simulator on top of [MockConnection]. Registers
     * written while 0xFF != 0 go to a separate per-page store, so the
     * private-bank tuning writes don't clobber page-0 registers. Starting a
     * ranging (or calibration) raises RESULT_INTERRUPT_STATUS; the interrupt
     * clear drops it unless continuous mode is active. The SPAD-info handshake
     * (page 7, 0x83) completes immediately.
     */
    class Sim : MockConnection() {
        val regs = ConcurrentHashMap<Int, Int>()
        val pages = ConcurrentHashMap<Pair<Int, Int>, Int>()
        val log = CopyOnWriteArrayList<Pair<Int, List<Int>>>()
        @Volatile var page = 0
        @Volatile var continuous = false

        init {
            set(0xC0, 0xEE, 0xAA, 0x10)
            set(0x84, 0x11)
            set(0xB0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
            set(0xF8, 0x00, 0x10)
            // Result block: status 11, 10.0 SPADs, 5.0 MCPS signal, 0.5 MCPS ambient, 250 mm.
            set(0x14, 11 shl 3, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x02, 0x80, 0x00, 0x40, 0x00, 0xFA)
            pages[1 to 0x91] = 0x3C
            pages[7 to 0x92] = 0x85
        }

        fun set(reg: Int, vararg values: Int) {
            values.forEachIndexed { i, v -> regs[reg + i] = v }
        }

        fun reg(r: Int): Int = regs[r] ?: 0
        fun reg16(r: Int): Int = (reg(r) shl 8) or reg(r + 1)
        fun pageReg(p: Int, r: Int): Int = pages[p to r] ?: 0
        fun page0Writes(r: Int): List<List<Int>> = log.filter { it.first == 0 && it.second.size >= 2 && it.second[0] == r }.map { it.second }
        fun logged(vararg bytes: Int): Boolean = log.any { it.second == bytes.toList() }
        fun lastWrite(back: Int): List<Int> = log[log.size - 1 - back].second

        @Synchronized
        override fun write(data: ByteArray) {
            val d = data.map { it.toInt() and 0xFF }
            val reg = d[0]
            if (reg == 0xFF) page = d[1]
            log.add(page to d)
            if (page != 0 && reg != 0xFF) {
                for (i in 1 until d.size) pages[page to reg + i - 1] = d[i]
                if (page == 7 && reg == 0x83 && d[1] == 0x00) pages[7 to 0x83] = 0x01
                return
            }
            for (i in 1 until d.size) regs[reg + i - 1] = d[i]
            if (reg == 0x00 && d.size == 2) {
                val v = d[1]
                if ((v and 0x06) != 0) {
                    continuous = true
                    regs[0x13] = 0x04
                } else if ((v and 0x01) != 0) {
                    if (continuous) continuous = false else regs[0x13] = 0x04
                }
                regs[0x00] = 0x00
            } else if (reg == 0x0B && d[1] == 0x01 && !continuous) {
                regs[0x13] = 0x00
            }
        }

        @Synchronized
        override fun writeRead(data: ByteArray, n: Int): ByteArray = ByteArray(n) { i ->
            val r = (data[0].toInt() and 0xFF) + i
            (if (page != 0) pageReg(page, r) else reg(r)).toByte()
        }
    }

    private fun tail(ws: List<List<Int>>, n: Int): List<Int> = ws.takeLast(n).map { it[1] }

    @Test
    fun rejectsWrongModelId() {
        val sim = Sim()
        sim.set(0xC0, 0xEF)
        assertThrows(IOException::class.java) { VL53L0XMinimal(sim) }
    }

    @Test
    fun initSequence() {
        val sim = Sim()
        VL53L0XMinimal(sim)
        assertEquals(0x01, sim.reg(0x89) and 0x01)
        assertEquals(0x00, sim.reg(0x88))
        assertEquals(listOf(0x60, 0x12), sim.page0Writes(0x60)[0])
        assertEquals(listOf(0x44, 0x00, 0x20), sim.page0Writes(0x44)[0])
        assertEquals(listOf(0x00, 0xF0, 0x01, 0, 0, 0), (0 until 6).map { sim.reg(0xB0 + it) })
        assertEquals(0xB4, sim.reg(0xB6))
        assertEquals(0x2C, sim.pageReg(1, 0x4E))
        assertEquals(0x25, sim.reg(0x46))
        assertEquals(0x05, sim.pageReg(1, 0x46))
        assertEquals(0x04, sim.reg(0x0A))
        assertEquals(0x01, sim.reg(0x84))
        assertEquals(0xE8, sim.reg(0x01))
        assertEquals(listOf(0x41, 0x00, 0x01, 0x00), tail(sim.page0Writes(0x00), 4))
        assertEquals(listOf(0xE8, 0x01, 0x02, 0xE8), tail(sim.page0Writes(0x01), 4))
        assertEquals(0, sim.page)
    }

    @Test
    fun singleShot() {
        val sim = Sim()
        val sensor = VL53L0XMinimal(sim)
        sim.log.clear()
        assertEquals(250, sensor.distance())
        assertTrue(sensor.rangeValid())
        val pre = listOf(listOf(0x80, 0x01), listOf(0xFF, 0x01), listOf(0x00, 0x00), listOf(0x91, 0x3C),
            listOf(0x00, 0x01), listOf(0xFF, 0x00), listOf(0x80, 0x00), listOf(0x00, 0x01))
        assertEquals(pre, sim.log.take(8).map { it.second })
        assertEquals(listOf(0x0B, 0x01), sim.lastWrite(0))
        sim.set(0x14, 4 shl 3)
        sim.set(0x1E, 0x1F, 0xFF)
        assertEquals(8191, sensor.distance())
        assertFalse(sensor.rangeValid())
    }

    @Test
    fun measurementAndContinuous() {
        val sim = Sim()
        val full = VL53L0XFull(sim)
        full.distance()
        assertEquals(VL53L0XFull.Measurement(250, 11, 5.0, 0.5, 10.0), full.readMeasurement())
        assertEquals(11, full.rangeStatus())

        sim.log.clear()
        full.startContinuous()
        assertEquals(listOf(0x00, 0x02), sim.lastWrite(0))
        assertTrue(sim.logged(0x91, 0x3C))
        assertTrue(full.dataReady())
        assertEquals(250, full.readContinuous())
        full.stopContinuous()
        val stop = listOf(listOf(0x00, 0x01), listOf(0xFF, 0x01), listOf(0x00, 0x00), listOf(0x91, 0x00),
            listOf(0x00, 0x01), listOf(0xFF, 0x00))
        assertEquals(stop, sim.log.takeLast(6).map { it.second })
        full.startContinuous(100)
        assertEquals(listOf(0x00, 0x00, 0x06, 0x40), (0 until 4).map { sim.reg(0x04 + it) })
        assertEquals(listOf(0x00, 0x04), sim.lastWrite(0))
        full.stopContinuous()
    }

    @Test
    fun timingVcselProfiles() {
        val sim = Sim()
        val full = VL53L0XFull(sim)
        assertTrue(full.timingBudget() in 32000..34000)
        full.setTimingBudget(50000)
        assertTrue(abs(full.timingBudget() - 50000) < 50)
        assertThrows(IllegalArgumentException::class.java) { full.setTimingBudget(19999) }

        full.setSignalRateLimit(0.1)
        assertEquals(13, sim.reg16(0x44))
        assertEquals(13 / 128.0, full.signalRateLimit())
        assertThrows(IllegalArgumentException::class.java) { full.setSignalRateLimit(-1.0) }

        assertEquals(14, full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE))
        assertEquals(10, full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.FINAL_RANGE))
        full.setVcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE, 18)
        assertEquals(18, full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE))
        assertEquals(0x50, sim.reg(0x57))
        full.setVcselPulsePeriod(VL53L0XFull.VcselPeriodType.FINAL_RANGE, 14)
        assertEquals(14, full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.FINAL_RANGE))
        assertEquals(listOf(0x48, 0x07, 0x20, 0xE8), listOf(sim.reg(0x48), sim.reg(0x30), sim.pageReg(1, 0x30), sim.reg(0x01)))
        assertTrue(abs(full.timingBudget() - 50000) < 300)
        assertThrows(IllegalArgumentException::class.java) {
            full.setVcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE, 13)
        }

        full.setProfile(VL53L0XFull.Profile.HIGH_SPEED)
        assertEquals(14, full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE))
        assertTrue(abs(full.timingBudget() - 20000) < 50)
        assertEquals(32, sim.reg16(0x44))
        full.setProfile(VL53L0XFull.Profile.LONG_RANGE)
        assertEquals(18, full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE))
        assertEquals(13, sim.reg16(0x44))
    }

    @Test
    fun offsetThresholdsInterrupts() {
        val sim = Sim()
        val full = VL53L0XFull(sim)
        full.setOffset(-10.25)
        assertEquals(-41 and 0x0FFF, sim.reg16(0x28))
        assertEquals(-10.25, full.offset())
        full.setOffset(12.5)
        assertEquals(12.5, full.offset())
        assertThrows(IllegalArgumentException::class.java) { full.setOffset(512.0) }
        full.setCrosstalkCompensation(0.5)
        assertEquals(4096, sim.reg16(0x20))
        assertThrows(IllegalArgumentException::class.java) { full.setCrosstalkCompensation(8.0) }

        sim.log.clear()
        full.recalibrate()
        assertTrue(sim.logged(0x00, 0x41) && sim.logged(0x01, 0x02))
        assertEquals(0xE8, sim.reg(0x01))

        full.setInterruptThresholds(100, 801)
        assertEquals(50, sim.reg16(0x0E))
        assertEquals(400, sim.reg16(0x0C))
        assertEquals(100 to 800, full.interruptThresholds())
        assertThrows(IllegalArgumentException::class.java) { full.setInterruptThresholds(500, 100) }
        full.setAddress(0x30)
        assertEquals(0x30, sim.reg(0x8A))
        assertThrows(IllegalArgumentException::class.java) { full.setAddress(0x78) }
        assertEquals(0xEE, full.modelId())
        assertEquals(0x10, full.revisionId())

        full.enableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW)
        full.disableInterrupt(VL53L0XFull.SOURCE_LEVEL_LOW)
        assertEquals(0x03, sim.reg(0x0A))
        full.disableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW)
        assertEquals(0x00, sim.reg(0x0A))
        sim.set(0x13, 0x03 or 0x08)
        assertEquals(VL53L0XFull.SOURCE_OUT_OF_WINDOW, full.pollInterrupt())
        assertEquals(0, sim.reg(0x13))
        assertEquals(0, full.pollInterrupt())
        assertThrows(IllegalArgumentException::class.java) { full.enableInterrupt(5) }

        // Polling fallback (no intPin on the mock).
        val got = CopyOnWriteArrayList<Int>()
        sim.set(0x13, 0x04)
        full.onInterrupt({ got.add(it) })
        var i = 0
        while (got.isEmpty() && i++ < 200) Thread.sleep(5)
        full.offInterrupt()
        assertEquals(listOf(VL53L0XFull.SOURCE_NEW_SAMPLE_READY), got.toList())
    }

    @Test
    fun timeout() {
        val sim = Sim()
        val full = VL53L0XFull(sim)
        full.startContinuous()
        sim.continuous = false
        sim.set(0x13, 0x00)
        assertThrows(IOException::class.java) { full.readContinuous() }
    }
}
