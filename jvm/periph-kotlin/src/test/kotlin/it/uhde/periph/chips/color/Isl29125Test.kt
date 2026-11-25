package it.uhde.periph.chips.color

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class Isl29125Test {

    companion object {
        @BeforeAll
        @JvmStatic
        fun noWaits() {
            Isl29125Minimal.pollStepMs = 0
        }
    }

    private fun mock(id: Int): MockConnection {
        val c = MockConnection()
        c.setRegister(0x00, id)
        c.setRegister(0x01, 0x00)
        c.setRegister(0x02, 0x00)
        c.setRegister(0x03, 0x00)
        c.setRegister(0x08, 0x02)
        return c
    }

    private fun reg(c: MockConnection, r: Int): Int = c.registers()[r]!!

    @Test
    fun initSequence() {
        val c = mock(0x7D)
        Isl29125Full(c)
        val seq = c.writes().filter { it.size > 1 }.map { listOf(it[0].toInt() and 0xFF, it[1].toInt() and 0xFF) }
        assertEquals(listOf(listOf(0x08, 0x00), listOf(0x03, 0x00), listOf(0x02, 0xBF), listOf(0x01, 0x0D)), seq)
    }

    @Test
    fun rejectsWrongId() {
        val c = mock(0x00)
        assertThrows(IllegalStateException::class.java) { Isl29125Minimal(c) }
        assertTrue(c.writes().all { it.size == 1 })
    }

    @Test
    fun readDecodesChannels() {
        val c = mock(0x7D)
        val chip = Isl29125Full(c)
        c.setRegister(0x08, 0x02)
        c.setRegister(0x09, 0x34, 0x12, 0xFF, 0xFF, 0x01, 0x00)
        val n = chip.read()
        assertEquals(0x1234, n.green)
        assertEquals(0xFFFF, n.red)
        assertEquals(1, n.blue)
        assertEquals(0xFFFF, chip.readRgb().red)
        assertEquals(0xFFFF, chip.readRed())
        assertEquals(0x1234, chip.readGreen())
        assertEquals(1, chip.readBlue())
        assertEquals(0x7D, chip.deviceId())
    }

    @Test
    fun configuration() {
        val c = mock(0x7D)
        val chip = Isl29125Full(c)
        c.setRegister(0x01, 0x0D)
        for (mode in 0..7) {
            chip.setMode(mode)
            assertEquals(0x08 or mode, reg(c, 0x01))
        }
        chip.setRange(375)
        assertEquals(0x07, reg(c, 0x01))
        chip.setRange(10000)
        assertEquals(0x0F, reg(c, 0x01))
        chip.setResolution(12)
        assertEquals(0x1F, reg(c, 0x01))
        chip.setResolution(16)
        assertEquals(0x0F, reg(c, 0x01))
        chip.setSync(true)
        assertEquals(0x2F, reg(c, 0x01))
        chip.setSync(false)
        assertEquals(0x0F, reg(c, 0x01))
        chip.setIrCompensation(false, 0x3F)
        assertEquals(0x3F, reg(c, 0x02))
        chip.setIrCompensation(true, 0x3F)
        assertEquals(0xBF, reg(c, 0x02))
    }

    @Test
    fun invalidArgumentsWriteNothing() {
        val c = mock(0x7D)
        val chip = Isl29125Full(c)
        val before = c.writes().size
        assertThrows(IllegalArgumentException::class.java) { chip.setMode(8) }
        assertThrows(IllegalArgumentException::class.java) { chip.setRange(100) }
        assertThrows(IllegalArgumentException::class.java) { chip.setResolution(8) }
        assertThrows(IllegalArgumentException::class.java) { chip.setIrCompensation(false, 64) }
        assertThrows(IllegalArgumentException::class.java) { chip.setPersistence(3) }
        assertThrows(IllegalArgumentException::class.java) { chip.setInterruptChannel(4) }
        assertThrows(IllegalArgumentException::class.java) { chip.setThresholds(5, 4) }
        assertThrows(IllegalArgumentException::class.java) { chip.setThresholds(0, 0x10000) }
        assertEquals(before, c.writes().size)
    }

    @Test
    fun softResetWritesMagicValue() {
        val c = mock(0x7D)
        val chip = Isl29125Full(c)
        val before = c.writes().size
        chip.softReset()
        val w = c.writes()[before]
        assertEquals(2, w.size)
        assertEquals(0x00, w[0].toInt() and 0xFF)
        assertEquals(0x46, w[1].toInt() and 0xFF)
    }

    @Test
    fun thresholds() {
        val c = mock(0x7D)
        val chip = Isl29125Full(c)
        chip.setThresholds(0x1234, 0xABCD)
        assertEquals(0x34, reg(c, 0x04))
        assertEquals(0x12, reg(c, 0x05))
        assertEquals(0xCD, reg(c, 0x06))
        assertEquals(0xAB, reg(c, 0x07))
        assertEquals(Pair(0x1234, 0xABCD), chip.getThresholds())
    }

    @Test
    fun interruptConfiguration() {
        val c = mock(0x7D)
        val chip = Isl29125Full(c)
        chip.setInterruptChannel(Isl29125Full.CHANNEL_RED)
        assertEquals(0x02, reg(c, 0x03))
        chip.setInterruptChannel(Isl29125Full.CHANNEL_BLUE)
        assertEquals(0x03, reg(c, 0x03))
        chip.setInterruptChannel(Isl29125Full.CHANNEL_NONE)
        chip.setPersistence(8)
        assertEquals(0x0C, reg(c, 0x03))
        chip.setPersistence(4)
        chip.setInterruptChannel(Isl29125Full.CHANNEL_GREEN)
        assertEquals(0x09, reg(c, 0x03))
        chip.enableInterrupt(Isl29125Full.SOURCE_CONVERSION)
        assertEquals(0x19, reg(c, 0x03))
        chip.disableInterrupt(Isl29125Full.SOURCE_CONVERSION)
        assertEquals(0x09, reg(c, 0x03))
        chip.disableInterrupt()
        assertEquals(0x08, reg(c, 0x03))
        chip.enableInterrupt()
        assertEquals(0x09, reg(c, 0x03))
        chip.setInterruptChannel(Isl29125Full.CHANNEL_RED)
        chip.enableInterrupt(Isl29125Full.SOURCE_THRESHOLD)
        assertEquals(0x0A, reg(c, 0x03))
    }

    @Test
    fun dataReadyKeepsThresholdFlag() {
        val c = mock(0x7D)
        val chip = Isl29125Full(c)
        c.setRegister(0x08, 0x03)
        assertTrue(chip.dataReady())
        c.setRegister(0x08, 0x00)
        assertFalse(chip.dataReady())
        assertEquals(0x03, chip.pollInterrupt())
        assertEquals(0x00, chip.pollInterrupt())
        c.setRegister(0x08, 0x04)
        assertEquals(0x04, chip.status())
    }

    @Test
    fun callbackReceivesStatus() {
        val c = mock(0x7D)
        val chip = Isl29125Full(c)
        val calls = AtomicInteger()
        val last = AtomicInteger()
        c.setRegister(0x08, 0x01)
        chip.onInterrupt { s -> calls.incrementAndGet(); last.set(s) }
        val end = System.currentTimeMillis() + 2000
        while (calls.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        c.setRegister(0x08, 0x00)
        chip.offInterrupt()
        assertTrue(calls.get() >= 1)
        assertEquals(0x01, last.get())
    }
}
