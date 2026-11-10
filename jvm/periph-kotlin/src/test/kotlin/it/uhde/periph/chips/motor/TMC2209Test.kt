package it.uhde.periph.chips.motor

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.UartConnectionMock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class TMC2209Test {

    private class Pin : OutputPin {
        val levels = mutableListOf<Boolean>()
        override fun set(high: Boolean) { levels.add(high) }
        override fun close() {}
    }

    @Test
    fun crcVectors() {
        assertEquals(0x7F, TMC2209Minimal.crc8(byteArrayOf(5, 0, 0x80.toByte(), 0, 0, 1, 0xC1.toByte(), 0), 7))
        assertEquals(0x6F, TMC2209Minimal.crc8(byteArrayOf(5, 0, 6, 0), 3))
    }

    @Test
    fun initWritesDefaults() {
        val mock = UartConnectionMock()
        TMC2209Minimal(mock)
        assertEquals(0x1C1L, mock.lastWrite(0x00))
        assertEquals(0x07L, mock.lastWrite(0x01))
        assertEquals(20L, mock.lastWrite(0x11))
        assertEquals(0xC10D0024L, mock.lastWrite(0x70))
        assertTrue(mock.datagrams().any { it.contentEquals(byteArrayOf(5, 0, 0x80.toByte(), 0, 0, 1, 0xC1.toByte(), 0x7F)) })
        assertTrue(mock.datagrams().any { it.contentEquals(byteArrayOf(5, 0, 6, 0x6F)) })
        val chop = mock.lastWrite(0x6C)
        assertEquals(3L, chop and 0xF)
        assertEquals(2L, (chop shr 15) and 3)
        assertEquals(5L, (chop shr 4) and 7)
        assertEquals(4L, (chop shr 24) and 0xF)
        assertEquals(1L, (chop shr 28) and 1)
        val ih = mock.lastWrite(0x10)
        assertEquals(8L, (ih shr 8) and 0x1F)
        assertEquals(2L, ih and 0x1F)
        assertEquals(6L, (ih shr 16) and 0xF)
    }

    @Test
    fun currentAndMotion() {
        val mock = UartConnectionMock()
        val m = TMC2209Minimal(mock)
        m.setCurrent(1.0)
        assertEquals(17L, (mock.lastWrite(0x10) shr 8) and 0x1F)
        m.setCurrent(5.0)
        assertEquals(31L, (mock.lastWrite(0x10) shr 8) and 0x1F)
        m.run(200.0)
        assertEquals(4474L, mock.lastWrite(0x22))
        m.run(-200.0)
        assertEquals((-4474L) and 0xFFFFFFL, mock.lastWrite(0x22))
        m.stop()
        assertEquals(0L, mock.lastWrite(0x22))
        m.disable()
        assertEquals(0L, mock.lastWrite(0x6C) and 0xF)
        m.enable()
        assertEquals(3L, mock.lastWrite(0x6C) and 0xF)
    }

    @Test
    fun statusAndFaults() {
        val mock = UartConnectionMock()
        val m = TMC2209Minimal(mock)
        mock.regs()[0x6F] = (1L shl 31) or 0x01 or 0x08 or 0x20
        assertTrue(m.isStandstill())
        assertEquals(
            TMC2209Minimal.FAULT_OVERTEMP_WARNING or TMC2209Minimal.FAULT_SHORT_GND or TMC2209Minimal.FAULT_SHORT_SUPPLY,
            m.readFault(),
        )
    }

    @Test
    fun enPinNoEchoAndErrors() {
        val pin = Pin()
        val m = TMC2209Minimal(UartConnectionMock(), 0.11, 0, pin, true)
        assertEquals(listOf(true), pin.levels)
        m.enable()
        assertFalse(pin.levels.last())

        val ne = UartConnectionMock(false)
        TMC2209Minimal(ne, 0.11, 0, null, false)
        assertEquals(0x1C1L, ne.lastWrite(0x00))

        val bad = UartConnectionMock()
        bad.regs()[0x06] = 0L
        assertThrows(IOException::class.java) { TMC2209Minimal(bad) }

        val flaky = UartConnectionMock()
        flaky.corruptNextReply()
        TMC2209Minimal(flaky)
        assertEquals(0x1C1L, flaky.lastWrite(0x00))
    }

    @Test
    fun fullConfiguration() {
        val mock = UartConnectionMock()
        val f = TMC2209Full(mock)
        f.setMicrosteps(256)
        assertEquals(0L, (mock.lastWrite(0x6C) shr 24) and 0xF)
        f.setMicrosteps(1)
        assertEquals(8L, (mock.lastWrite(0x6C) shr 24) and 0xF)
        assertThrows(IllegalArgumentException::class.java) { f.setMicrosteps(3) }
        f.setMicrosteps(32)
        f.run(100.0)
        assertEquals(4474L, mock.lastWrite(0x22))
        f.setInterpolation(false)
        assertEquals(0L, (mock.lastWrite(0x6C) shr 28) and 1)
        f.setChopperMode(true)
        f.setDirectionInverted(true)
        f.setIndexOutput(1)
        val g = mock.lastWrite(0x00)
        assertEquals(0x4L, g and 0x4)
        assertEquals(0x8L, g and 0x8)
        assertEquals(0x10L, g and 0x30)
        f.setDoubleEdge(true)
        f.setDriverProtection(false, true)
        var c = mock.lastWrite(0x6C)
        assertEquals(1L, (c shr 29) and 1)
        assertEquals(1L, (c shr 31) and 1)
        assertEquals(0L, (c shr 30) and 1)
        f.setVsense(true)
        assertEquals(1L, (mock.lastWrite(0x6C) shr 17) and 1)
        f.setHoldCurrent(0.2)
        assertEquals(6L, mock.lastWrite(0x10) and 0x1F)
        f.setCurrentDelay(2, 100)
        assertEquals(2L, (mock.lastWrite(0x10) shr 16) and 0xF)
        assertEquals(100L, mock.lastWrite(0x11))
        f.setStealthchopThreshold(300)
        assertEquals(300L, mock.lastWrite(0x13))
        f.configureChopper(4, 1, 3, 2)
        c = mock.lastWrite(0x6C)
        assertEquals(4L, c and 0xF)
        assertEquals(1L, (c shr 15) and 3)
        assertEquals(3L, (c shr 4) and 7)
        assertEquals(2L, (c shr 7) and 0xF)
        f.configureStealthchop(true, false, 40, 5, 1, 4, 12)
        val p = mock.lastWrite(0x70)
        assertEquals(40L, p and 0xFF)
        assertEquals(5L, (p shr 8) and 0xFF)
        assertEquals(1L, (p shr 16) and 3)
        assertEquals(1L, (p shr 18) and 1)
        assertEquals(0L, (p shr 19) and 1)
        assertEquals(4L, (p shr 24) and 0xF)
        assertEquals(12L, p shr 28)
        f.setFreewheel(1)
        assertEquals(1L, (mock.lastWrite(0x70) shr 20) and 3)
        f.setSendDelay(4)
        assertEquals(4L shl 8, mock.lastWrite(0x03))
        assertThrows(IllegalArgumentException::class.java) { f.setSendDelay(16) }
        f.setInternalRsense(true)
        assertThrows(IllegalStateException::class.java) { f.setCurrent(0.5) }
        f.setInternalRsense(false)
    }

    @Test
    fun fullReadbacks() {
        val mock = UartConnectionMock()
        val f = TMC2209Full(mock)
        mock.regs()[0x6A] = 513L
        mock.regs()[0x6B] = (0x1FFL shl 16) or 0x100
        mock.regs()[0x12] = 1234L
        mock.regs()[0x6F] = (1L shl 31) or (1L shl 30) or (17L shl 16) or (0x5L shl 8) or 0x02
        mock.regs()[0x71] = (0x1FEL shl 16) or 77
        mock.regs()[0x72] = (9L shl 16) or 33
        mock.regs()[0x01] = 0x03L
        mock.regs()[0x05] = 0x123456L
        assertEquals(513, f.microstepPosition())
        assertEquals(TMC2209Full.CoilCurrents(-256, -1), f.readCoilCurrents())
        assertEquals(1234L, f.readTstep())
        assertEquals(17, f.readCurrentScale())
        assertEquals(5, f.readTemperatureFlags())
        val st = f.readStatus()
        assertTrue(st.stst && st.stealth && st.ot && st.t120 && st.t150 && !st.t143)
        assertEquals(17, st.csActual)
        assertEquals(TMC2209Full.GlobalStatus(true, true, false), f.readGlobalStatus())
        f.readGlobalStatus(true)
        assertEquals(0x07L, mock.lastWrite(0x01))
        assertEquals(TMC2209Full.PwmAuto(77, -2, 33, 9), f.readPwmAuto())
        mock.regs()[0x06] = 0x21000000L or 0x04 or 0x10 or 0x200
        val io = f.readIoin()
        assertTrue(io.ms1 && io.diag && io.dir)
        assertFalse(io.ms2)
        assertEquals(0x21, io.version)
        assertEquals(0x123456L, f.readOtp())
        assertEquals(mock.regs()[0x02]!!.toInt(), f.readIfcnt())
        assertEquals(513L, f.readRegister(0x6A))
        assertThrows(IllegalArgumentException::class.java) { f.readRegister(0x10) }
        f.writeRegister(0x13, 5)
        assertEquals(5L, mock.lastWrite(0x13))
        assertThrows(IllegalArgumentException::class.java) { f.writeRegister(0x04, 1) }
    }

    @Test
    fun stepPulsesAndIdentity() {
        val step = Pin()
        val dir = Pin()
        val f = TMC2209Full(UartConnectionMock(), 0.11, 0, null, true, step, dir)
        f.step(3, 0.0)
        assertEquals(3, step.levels.count { it })
        assertTrue(dir.levels.last())
        f.step(-2, 0.0)
        assertFalse(dir.levels.last())
        val nopin = TMC2209Full(UartConnectionMock())
        assertThrows(IllegalStateException::class.java) { nopin.step(1, 0.0) }
    }

    @Test
    fun rejectsTmc2208AndChecksAddress() {
        val old = UartConnectionMock()
        old.regs()[0x06] = 0x20000100L
        assertThrows(IOException::class.java) { TMC2209Minimal(old) }

        val am = UartConnectionMock()
        TMC2209Minimal(am, 0.11, 2, null, true)
        assertTrue(am.datagrams().all { it[1].toInt() == 2 })
        val gconf = am.datagrams()[2]
        assertEquals(TMC2209Minimal.crc8(gconf, 7), gconf[7].toInt() and 0xFF)
        assertThrows(IllegalArgumentException::class.java) { TMC2209Minimal(UartConnectionMock(), 0.11, 4, null, true) }
        assertThrows(IllegalArgumentException::class.java) { TMC2209Minimal(UartConnectionMock(), 0.11, -1, null, true) }
    }

    @Test
    fun multiNodeWritesSenddelayFirst() {
        val mn = UartConnectionMock()
        TMC2209Full(mn, multiNode = true)
        assertEquals(0x03L, mn.regWrites()[0][0])
        assertEquals(2L shl 8, mn.regWrites()[0][1])
        assertEquals(7L, mn.regs()[0x02])
    }

    private class DiagPin(private val level: Boolean) : InputPin {
        override fun isHigh() = level
        override fun onEdge(handler: EdgeHandler, trigger: EdgeTrigger) {}
        override fun offEdge(handler: EdgeHandler) {}
        override fun close() {}
    }

    @Test
    fun stallGuardAndCoolStep() {
        val mock = UartConnectionMock()
        val f = TMC2209Full(mock, diagPin = DiagPin(true))
        f.setStallThreshold(60)
        assertEquals(60L, mock.lastWrite(0x40))
        assertThrows(IllegalArgumentException::class.java) { f.setStallThreshold(256) }
        f.setCoolstepThreshold(500)
        assertEquals(500L, mock.lastWrite(0x14))
        assertThrows(IllegalArgumentException::class.java) { f.setCoolstepThreshold(1L shl 20) }
        f.configureCoolstep(5, 2, 1, 1, 0)
        assertEquals((5 or (1 shl 5) or (2 shl 8) or (1 shl 13)).toLong(), mock.lastWrite(0x42))
        f.configureCoolstep(0, 0, 0, 0, 1)
        assertEquals((1 shl 15).toLong(), mock.lastWrite(0x42))
        assertThrows(IllegalArgumentException::class.java) { f.configureCoolstep(16, 0, 0, 0, 0) }
        mock.regs()[0x41] = 0x1FEL
        assertEquals(510, f.readStallguard())
        assertTrue(f.isStalled())
        assertThrows(IllegalStateException::class.java) { TMC2209Full(UartConnectionMock()).isStalled() }
    }
}
