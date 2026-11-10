package it.uhde.periph.chips.motor

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class TMC2130Test {

    /** Scripted TMC2130 implementing the pipelined 40-bit datagram protocol. */
    private class Sim : Connection {
        val regs = mutableMapOf<Int, Long>(0x04 to 0x11000040L, 0x01 to 1L)
        val frames = mutableListOf<ByteArray>()
        val writes = mutableListOf<Pair<Int, Long>>()
        var pending = 0L
        var mirrorBroken = false
        var gstatReads = 0

        fun lastWrite(reg: Int): Long = writes.last { it.first == reg }.second

        private fun datagram(data: ByteArray): Long {
            val f = data.copyOf(5)
            frames.add(f)
            val reply = pending
            val reg = f[0].toInt() and 0x7F
            if ((f[0].toInt() and 0x80) != 0) {
                val v = ((f[1].toLong() and 0xFF) shl 24) or ((f[2].toLong() and 0xFF) shl 16) or
                    ((f[3].toLong() and 0xFF) shl 8) or (f[4].toLong() and 0xFF)
                regs[reg] = v
                writes.add(reg to v)
                pending = if (mirrorBroken) 0 else v
            } else {
                if (reg == 0x01) gstatReads++
                pending = regs[reg] ?: 0L
                if (reg == 0x01) regs[0x01] = 0L
            }
            return reply
        }

        override fun write(data: ByteArray) {
            assertEquals(5, data.size)
            datagram(data)
        }

        override fun read(n: Int) = ByteArray(n)

        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            assertEquals(4, n)
            val r = datagram(data)
            return byteArrayOf((r shr 24).toByte(), (r shr 16).toByte(), (r shr 8).toByte(), r.toByte())
        }

        override fun enable() {}
        override fun disable() {}
        override fun isEnabled() = true
        override fun intPin(): InputPin? = null
        override fun enPin(): OutputPin? = null
        override fun close() {}
    }

    private class Pin : OutputPin {
        val levels = mutableListOf<Boolean>()
        override fun set(high: Boolean) { levels.add(high) }
        override fun close() {}
        fun highs() = levels.count { it }
    }

    private class DiagPin : InputPin {
        override fun isHigh() = true
        override fun onEdge(handler: EdgeHandler, trigger: EdgeTrigger) {}
        override fun offEdge(handler: EdgeHandler) {}
        override fun close() {}
    }

    @Test
    fun initWritesDefaults() {
        val sim = Sim()
        TMC2130Minimal(sim, Pin(), Pin())
        assertTrue(sim.frames.any { it.contentEquals(byteArrayOf(0xEC.toByte(), 0x14, 0x03, 0x00, 0xC3.toByte())) })
        assertEquals(10L, sim.lastWrite(0x11))
        assertEquals(0x4L, sim.lastWrite(0x00))
        assertEquals(500L, sim.lastWrite(0x13))
        assertEquals(0x000401C8L, sim.lastWrite(0x70))
        val ih = sim.lastWrite(0x10)
        assertEquals(15L, (ih shr 8) and 0x1F)
        assertEquals(5L, ih and 0x1F)
        assertEquals(6L, (ih shr 16) and 0xF)
        assertEquals(1, sim.gstatReads)
    }

    @Test
    fun initFailures() {
        val a = Sim().also { it.regs[0x04] = 0L }
        assertThrows(IOException::class.java) { TMC2130Minimal(a, Pin(), Pin()) }
        val b = Sim().also { it.regs[0x04] = 0x11000000L }
        assertThrows(IOException::class.java) { TMC2130Minimal(b, Pin(), Pin()) }
        val c = Sim().also { it.mirrorBroken = true }
        assertThrows(IOException::class.java) { TMC2130Minimal(c, Pin(), Pin()) }
    }

    @Test
    fun currentMotionAndFaults() {
        val sim = Sim()
        val step = Pin()
        val dir = Pin()
        val m = TMC2130Minimal(sim, step, dir)
        m.setCurrent(1.0)
        assertEquals(17L, (sim.lastWrite(0x10) shr 8) and 0x1F)
        assertEquals(0L, sim.lastWrite(0x6C) and (1L shl 17))
        m.setCurrent(0.5)
        assertEquals(15L, (sim.lastWrite(0x10) shr 8) and 0x1F)
        assertTrue((sim.lastWrite(0x6C) and (1L shl 17)) != 0L)
        assertThrows(IllegalArgumentException::class.java) { m.setCurrent(1.5) }
        m.move(2, 1e6)
        assertEquals(32, step.highs())
        assertTrue(dir.levels.last())
        m.move(-1, 1e6)
        assertFalse(dir.levels.last())
        assertEquals(48, step.highs())
        assertThrows(IllegalArgumentException::class.java) { m.move(1, 0.0) }
        m.disable()
        assertEquals(0L, sim.lastWrite(0x6C) and 0xF)
        m.enable()
        assertEquals(3L, sim.lastWrite(0x6C) and 0xF)
        sim.regs[0x6F] = (1L shl 31) or (1L shl 26) or (1L shl 28) or (1L shl 30)
        assertTrue(m.isStandstill())
        assertEquals(
            TMC2130Minimal.FAULT_OVERTEMP_WARNING or TMC2130Minimal.FAULT_SHORT_GND or TMC2130Minimal.FAULT_OPEN_LOAD,
            m.readFault(),
        )
    }

    @Test
    fun enPin() {
        val en = Pin()
        val m = TMC2130Minimal(Sim(), Pin(), Pin(), 0.11, en)
        assertEquals(listOf(true), en.levels)
        m.enable()
        assertFalse(en.levels.last())
    }

    @Test
    fun fullConfiguration() {
        val sim = Sim()
        val step = Pin()
        val dir = Pin()
        val f = TMC2130Full(sim, step, dir, 0.11, null, DiagPin(), null)
        f.setMicrosteps(256)
        assertEquals(0L, (sim.lastWrite(0x6C) shr 24) and 0xF)
        f.setMicrosteps(1)
        assertEquals(8L, (sim.lastWrite(0x6C) shr 24) and 0xF)
        assertThrows(IllegalArgumentException::class.java) { f.setMicrosteps(3) }
        f.setMicrosteps(32)
        f.move(1, 1e6)
        assertEquals(32, step.highs())
        f.setChopperMode(true)
        assertEquals(0L, sim.lastWrite(0x00) and 0x4)
        f.setChopperMode(false)
        assertEquals(0x4L, sim.lastWrite(0x00) and 0x4)
        f.setDirectionInverted(true)
        assertEquals(0x10L, sim.lastWrite(0x00) and 0x10)
        f.configureDiag(diag0Stall = true, diag1Index = true, diag0Pushpull = true)
        val g = sim.lastWrite(0x00)
        assertEquals(1L, (g shr 7) and 1)
        assertEquals(1L, (g shr 9) and 1)
        assertEquals(1L, (g shr 12) and 1)
        assertEquals(0L, (g shr 5) and 1)
        assertEquals(0x14L, g and 0x14)
        f.setVsense(false)
        f.setCurrent(0.5)
        assertEquals(8L, (sim.lastWrite(0x10) shr 8) and 0x1F)
        assertEquals(0L, (sim.lastWrite(0x6C) shr 17) and 1)
        f.setHoldCurrent(0.2)
        assertEquals(3L, sim.lastWrite(0x10) and 0x1F)
        f.setCurrentDelay(2, 100)
        assertEquals(2L, (sim.lastWrite(0x10) shr 16) and 0xF)
        assertEquals(100L, sim.lastWrite(0x11))
        f.configureChopper(4, 1, 3, 2)
        var c = sim.lastWrite(0x6C)
        assertEquals(4L, c and 0xF)
        assertEquals(1L, (c shr 15) and 3)
        assertEquals(3L, (c shr 4) and 7)
        assertEquals(2L, (c shr 7) and 0xF)
        assertEquals(0L, (c shr 14) and 1)
        f.configureConstantOffTime(5, 2, 11, -3, true, true)
        c = sim.lastWrite(0x6C)
        assertEquals(5L, c and 0xF)
        assertEquals(1L, (c shr 14) and 1)
        assertEquals(3L, (c shr 4) and 7)
        assertEquals(1L, (c shr 11) and 1)
        assertEquals(0L, (c shr 7) and 0xF)
        assertEquals(3L, (c shr 12) and 3)
        f.configureStealthchop(true, false, 40, 5, 1)
        f.setFreewheel(1)
        val p = sim.lastWrite(0x70)
        assertEquals(40L, p and 0xFF)
        assertEquals(5L, (p shr 8) and 0xFF)
        assertEquals(1L, (p shr 16) and 3)
        assertEquals(1L, (p shr 20) and 3)
        assertEquals(1L, (p shr 18) and 1)
        f.setStallThreshold(-5)
        f.setStallguardFilter(true)
        f.configureCoolstep(5, 2, 1, 1, 0)
        val cc = sim.lastWrite(0x6D)
        assertEquals(5L, cc and 0xF)
        assertEquals((-5 and 0x7F).toLong(), (cc shr 16) and 0x7F)
        assertEquals(1L, (cc shr 24) and 1)
        assertEquals(2L, (cc shr 8) and 0xF)
        assertThrows(IllegalArgumentException::class.java) { f.setStallThreshold(64) }
        assertThrows(IllegalArgumentException::class.java) { f.configureCoolstep(16, 0, 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { f.setCoolstepThreshold(1L shl 20) }
        f.setCoolstepThreshold(500)
        assertEquals(500L, sim.lastWrite(0x14))
        assertThrows(IllegalStateException::class.java) { f.setCoilCurrents(1, 1) }
        f.setDirectMode(true)
        f.setCoilCurrents(100, -100)
        assertEquals(100L or ((-100L and 0x1FF) shl 16), sim.lastWrite(0x2D))
        f.setDcstep(1000, 200, 3)
        assertEquals(1000L, sim.lastWrite(0x33))
        assertEquals(200L or (3L shl 16), sim.lastWrite(0x6E))
        f.writeMicrostepTable(longArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 0x12345678L, 0x00F70000L)
        assertEquals(1L, sim.lastWrite(0x60))
        assertEquals(8L, sim.lastWrite(0x67))
        assertEquals(0x12345678L, sim.lastWrite(0x68))
        f.setInternalRsense(true)
        assertThrows(IllegalStateException::class.java) { f.setCurrent(0.5) }
        f.setInternalRsense(false)
        f.step(3, 0.0)
        assertEquals(35, step.highs())
        assertTrue(dir.levels.last())
        f.step(-2, 0.0)
        assertFalse(dir.levels.last())
    }

    @Test
    fun fullReadbacks() {
        val sim = Sim()
        val f = TMC2130Full(sim, Pin(), Pin(), 0.11, null, DiagPin(), null)
        sim.regs[0x6A] = 513L
        sim.regs[0x6B] = (0x1FFL shl 16) or 0x100
        sim.regs[0x12] = 1234L
        sim.regs[0x71] = 77L
        sim.regs[0x73] = 0x123456L
        sim.regs[0x6F] = (1L shl 31) or (1L shl 15) or (17L shl 16) or (1L shl 25) or (1L shl 24) or 300
        sim.regs[0x04] = 0x11000000L or 0x40 or 0x1 or 0x10
        sim.regs[0x01] = 3L
        assertEquals(513, f.microstepPosition())
        assertEquals(TMC2130Full.CoilCurrents(-1, -256), f.readCoilCurrents())
        assertEquals(1234L, f.readTstep())
        assertEquals(77, f.readPwmScale())
        assertEquals(0x23456L, f.readLostSteps())
        assertEquals(17, f.readCurrentScale())
        assertEquals(300, f.readStallguard())
        assertTrue(f.isStalled())
        val st = f.readStatus()
        assertTrue(st.stst && st.fsactive && st.ot && st.stallguard && !st.otpw)
        assertEquals(17, st.csActual)
        assertEquals(300, st.sgResult)
        assertEquals(TMC2130Full.GlobalStatus(true, true, false), f.readGlobalStatus())
        assertEquals(0L, sim.regs[0x01])
        val io = f.readIoin()
        assertTrue(io.step && io.enn && !io.dir)
        assertEquals(0x11, io.version)
        assertEquals(513L, f.readRegister(0x6A))
        assertThrows(IllegalArgumentException::class.java) { f.readRegister(0x10) }
        f.writeRegister(0x13, 5, true)
        assertEquals(5L, sim.lastWrite(0x13))
        assertThrows(IllegalArgumentException::class.java) { f.writeRegister(0x72, 1) }
        assertTrue(f.readDiag(0))
        assertThrows(IllegalStateException::class.java) { f.readDiag(1) }
        assertThrows(IllegalArgumentException::class.java) { f.readDiag(2) }
    }
}
