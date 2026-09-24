package it.uhde.periph.chips.tof

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

class VL53L1XTest {

    /**
     * VL53L1X simulator on top of [MockConnection] with explicit 16-bit
     * register indices. GPIO__TIO_HV_STATUS (0x0031) is computed: bit 0 is 0
     * (active-low line asserted) while a result is pending. Starting a
     * single-shot or timed ranging makes a result pending; the interrupt clear
     * drops it unless timed ranging is running.
     */
    class Sim : MockConnection() {
        val regs = ConcurrentHashMap<Int, Int>()
        val log = CopyOnWriteArrayList<List<Int>>()
        @Volatile var pending = false
        @Volatile var ranging = false

        init {
            set(0x00E5, 0x01)
            set(0x010F, 0xEA, 0xCC, 0x10)
            set(0x013E, 0x91)
            set(0x00DE, 0x00, 0x50)
            // Result block: raw status 9 (valid), 10.0 SPADs, 0.5 MCPS ambient, 250 mm, 5.0 MCPS signal.
            set(0x0089, 9, 0, 0, 0x0A, 0x00, 0, 0, 0x00, 0x40, 0, 0, 0, 0, 0x00, 0xFA, 0x02, 0x80)
        }

        fun set(reg: Int, vararg values: Int) = values.forEachIndexed { i, v -> regs[reg + i] = v }
        fun reg(r: Int): Int = regs.getOrDefault(r, 0)
        fun reg16(r: Int): Int = (reg(r) shl 8) or reg(r + 1)
        fun writesTo(r: Int): List<Int> = log.filter { it.size == 3 && ((it[0] shl 8) or it[1]) == r }.map { it[2] }

        @Synchronized
        override fun write(data: ByteArray) {
            val w = data.map { it.toInt() and 0xFF }
            log += w
            val reg = (w[0] shl 8) or w[1]
            for (i in 2 until w.size) regs[reg + i - 2] = w[i]
            if (reg == 0x0087 && w.size == 3) {
                if (w[2] == 0x10 || w[2] == 0x40) {
                    pending = true
                    ranging = w[2] == 0x40
                } else {
                    ranging = false
                }
            } else if (reg == 0x0086 && w[2] == 0x01) {
                pending = ranging
            }
        }

        @Synchronized
        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            val reg = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            return ByteArray(n) { i ->
                val r = reg + i
                (if (r == 0x0031) (if (pending) 0x00 else 0x01) else reg(r)).toByte()
            }
        }
    }

    /** InputPin whose single handler the test triggers by hand. */
    class FakePin : InputPin {
        var handler: EdgeHandler? = null
        override fun onEdge(handler: EdgeHandler, trigger: EdgeTrigger) {
            this.handler = handler
        }
        override fun offEdge(handler: EdgeHandler) {
            this.handler = null
        }
        override fun close() {}
    }

    @Test
    fun rejectsWrongSensorIdAndBootTimeout() {
        val bad = Sim().apply { set(0x0110, 0xCD) }
        assertThrows(IOException::class.java) { VL53L1XMinimal(bad) }
        val unbooted = Sim().apply { set(0x00E5, 0x00) }
        assertThrows(IOException::class.java) { VL53L1XMinimal(unbooted) }
    }

    @Test
    fun initSequence() {
        val sim = Sim()
        VL53L1XMinimal(sim)
        for (i in 0 until 91) {
            val w = sim.log[i]
            assertEquals(3, w.size)
            assertEquals(0x2D + i, (w[0] shl 8) or w[1])
        }
        assertEquals(0x20, sim.writesTo(0x0046)[0])
        assertEquals(0x9B, sim.writesTo(0x0081)[0])
        assertEquals(listOf(0x01, 0x01, 0x11), listOf(sim.reg(0x002E), sim.reg(0x002F), sim.reg(0x0030)))
        assertEquals(listOf(0x40, 0x00), sim.writesTo(0x0087).takeLast(2))
        assertEquals(0x09, sim.reg(0x0008))
        assertEquals(0x00, sim.reg(0x000B))
        assertFalse(sim.ranging)
    }

    @Test
    fun singleShot() {
        val sim = Sim()
        val sensor = VL53L1XMinimal(sim)
        sim.log.clear()
        assertEquals(250, sensor.distance())
        assertTrue(sensor.rangeValid())
        assertEquals(listOf(0x00, 0x86, 0x01), sim.log[0])
        assertEquals(listOf(0x00, 0x87, 0x10), sim.log[1])
        assertEquals(listOf(0x00, 0x86, 0x01), sim.log.last())
        sim.set(0x0089, 4)
        assertEquals(250, sensor.distance())
        assertFalse(sensor.rangeValid())
    }

    @Test
    fun measurementBudgetMode() {
        val sim = Sim()
        val full = VL53L1XFull(sim)
        full.distance()
        assertEquals(VL53L1XFull.Measurement(250, 0, 5.0, 0.5, 10.0), full.readMeasurement())
        assertEquals(0, full.rangeStatus())
        sim.set(0x0089, 0x1F)
        assertEquals(255, full.readMeasurement().rangeStatus)
        sim.set(0x0089, 9)

        assertEquals(100000, full.timingBudget())
        assertEquals(VL53L1XFull.DistanceMode.LONG, full.distanceMode())
        full.setTimingBudget(33000)
        assertEquals(0x0060, sim.reg16(0x005E))
        assertEquals(0x006E, sim.reg16(0x0061))
        assertEquals(33000, full.timingBudget())
        assertThrows(IllegalArgumentException::class.java) { full.setTimingBudget(15000) }
        assertThrows(IllegalArgumentException::class.java) { full.setTimingBudget(40000) }
        full.setDistanceMode(VL53L1XFull.DistanceMode.SHORT)
        assertEquals(listOf(0x14, 0x07, 0x05, 0x38, 0x0705, 0x0606, 0x00D6),
            listOf(sim.reg(0x004B), sim.reg(0x0060), sim.reg(0x0063), sim.reg(0x0069), sim.reg16(0x0078),
                sim.reg16(0x007A), sim.reg16(0x005E)))
        full.setTimingBudget(15000)
        assertEquals(0x001D, sim.reg16(0x005E))
        assertEquals(0x0027, sim.reg16(0x0061))
        assertThrows(IllegalArgumentException::class.java) { full.setDistanceMode(VL53L1XFull.DistanceMode.LONG) }
        full.setTimingBudget(100000)
        full.setDistanceMode(VL53L1XFull.DistanceMode.LONG)
        assertEquals(listOf(0x0A, 0x0F0D, 0x01CC, 0x01EA),
            listOf(sim.reg(0x004B), sim.reg16(0x0078), sim.reg16(0x005E), sim.reg16(0x0061)))
        sim.set(0x004B, 0x33)
        assertThrows(IOException::class.java) { full.distanceMode() }
    }

    @Test
    fun continuousAndThresholds() {
        val sim = Sim()
        val full = VL53L1XFull(sim)
        full.setInterMeasurement(200)
        assertEquals(0x50 * 200 * 1075 / 1000, (sim.reg16(0x006C) shl 16) or sim.reg16(0x006E))
        assertEquals(200, full.interMeasurement())
        assertThrows(IllegalArgumentException::class.java) { full.setInterMeasurement(0) }
        full.startContinuous()
        assertEquals(100, full.interMeasurement())
        assertEquals(listOf(0x00, 0x87, 0x40), sim.log.last())
        assertTrue(full.dataReady())
        assertEquals(250, full.readContinuous())
        assertTrue(full.dataReady())
        full.stopContinuous()
        assertEquals(listOf(0x00, 0x87, 0x00), sim.log.last())
        full.startContinuous(50)
        assertEquals(100, full.interMeasurement())
        full.stopContinuous()
        full.startContinuous(500)
        assertEquals(500, full.interMeasurement())
        full.stopContinuous()
        assertThrows(IllegalArgumentException::class.java) { full.startContinuous(-1) }

        full.setInterruptThresholds(100, 801)
        assertEquals(100, sim.reg16(0x0074))
        assertEquals(801, sim.reg16(0x0072))
        assertEquals(Pair(100, 801), full.interruptThresholds())
        assertThrows(IllegalArgumentException::class.java) { full.setInterruptThresholds(500, 100) }
    }

    @Test
    fun signalSigmaRoiOffsetCrosstalk() {
        val sim = Sim()
        val full = VL53L1XFull(sim)
        assertEquals(1.0, full.signalRateLimit())
        full.setSignalRateLimit(0.25)
        assertEquals(32, sim.reg16(0x0066))
        assertThrows(IllegalArgumentException::class.java) { full.setSignalRateLimit(-1.0) }
        assertEquals(90, full.sigmaThreshold())
        full.setSigmaThreshold(45)
        assertEquals(180, sim.reg16(0x0064))
        assertThrows(IllegalArgumentException::class.java) { full.setSigmaThreshold(16384) }

        assertEquals(Pair(16, 16), full.roi())
        assertEquals(199, full.roiCenter())
        full.setRoiCenter(167)
        full.setRoi(8, 8)
        assertEquals(0x77, sim.reg(0x0080))
        assertEquals(167, full.roiCenter())
        full.setRoi(8, 16)
        assertEquals(Pair(8, 16), full.roi())
        assertEquals(199, full.roiCenter())
        assertThrows(IllegalArgumentException::class.java) { full.setRoi(3, 8) }
        assertEquals(0x91, full.opticalCenter())

        full.setOffset(-10.25)
        assertEquals(-41 and 0x1FFF, sim.reg16(0x001E))
        assertEquals(-10.25, full.offset())
        full.setOffset(700.5)
        assertEquals(700.5, full.offset())
        assertThrows(IllegalArgumentException::class.java) { full.setOffset(1024.0) }
        full.setCrosstalkCompensation(0.01)
        assertEquals(5120, sim.reg16(0x0016))
        assertEquals(0.01, full.crosstalkCompensation())
        assertThrows(IllegalArgumentException::class.java) { full.setCrosstalkCompensation(0.128) }
    }

    @Test
    fun calibrationAndRecalibrate() {
        val sim = Sim()
        val full = VL53L1XFull(sim)
        assertEquals(10.0, full.calibrateOffset(260))
        assertEquals(10.0, full.offset())
        assertFalse(sim.ranging)
        assertEquals(0.127, full.calibrateCrosstalk(500))
        sim.set(0x0098, 0x00, 0x20)
        assertTrue(abs(full.calibrateCrosstalk(500) - 0.0125) < 1e-9)
        assertEquals(6400, sim.reg16(0x0016))
        assertThrows(IllegalArgumentException::class.java) { full.calibrateCrosstalk(0) }

        sim.log.clear()
        full.recalibrate()
        assertEquals(listOf(0x81, 0x09), sim.writesTo(0x0008))
        assertEquals(listOf(0x92, 0x00), sim.writesTo(0x000B))
        assertEquals(listOf(0x40, 0x00), sim.writesTo(0x0087))
    }

    @Test
    fun addressIdentificationInterrupts() {
        val sim = Sim()
        val full = VL53L1XFull(sim)
        full.setAddress(0x30)
        assertEquals(0x30, sim.reg(0x0001))
        assertThrows(IllegalArgumentException::class.java) { full.setAddress(0x78) }
        assertEquals(0xEA, full.modelId())
        assertEquals(0xCC, full.moduleType())
        assertEquals(0x10, full.revisionId())

        full.enableInterrupt(VL53L1XFull.SOURCE_OUT_OF_WINDOW)
        assertEquals(0x02, sim.reg(0x0046))
        full.enableInterrupt(VL53L1XFull.SOURCE_IN_WINDOW)
        full.disableInterrupt(VL53L1XFull.SOURCE_LEVEL_LOW)
        assertEquals(0x03, sim.reg(0x0046))
        full.disableInterrupt(VL53L1XFull.SOURCE_IN_WINDOW)
        full.disableInterrupt(VL53L1XFull.SOURCE_NEW_SAMPLE_READY)
        assertEquals(0x20, sim.reg(0x0046))
        sim.pending = false
        assertEquals(0, full.pollInterrupt())
        full.enableInterrupt(VL53L1XFull.SOURCE_OUT_OF_WINDOW)
        sim.pending = true
        assertEquals(VL53L1XFull.SOURCE_OUT_OF_WINDOW, full.pollInterrupt())
        assertFalse(sim.pending)
        assertThrows(IllegalArgumentException::class.java) { full.enableInterrupt(6) }

        full.enableInterrupt(VL53L1XFull.SOURCE_NEW_SAMPLE_READY)
        sim.pending = true
        val pin = FakePin()
        val got = mutableListOf<Int>()
        full.onInterrupt({ got += it }, pin)
        pin.handler!!.onEdge()
        full.offInterrupt()
        assertEquals(listOf(VL53L1XFull.SOURCE_NEW_SAMPLE_READY), got)
        assertNull(pin.handler)
    }
}
