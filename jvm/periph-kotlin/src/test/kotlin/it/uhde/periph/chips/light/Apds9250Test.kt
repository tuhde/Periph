package it.uhde.periph.chips.light

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class Apds9250Test {

    companion object {
        @BeforeAll
        @JvmStatic
        fun noWaits() {
            Apds9250Minimal.startWaitMs = 0
            Apds9250Minimal.pollStepMs = 0
        }
    }

    private fun mock(part: Int): MockConnection {
        val c = MockConnection()
        c.setRegister(0x06, part)
        c.setRegister(0x07, 0x08)
        c.setRegister(0x04, 0x22)
        c.setRegister(0x05, 0x01)
        c.setRegister(0x19, 0x10)
        return c
    }

    private fun reg(c: MockConnection, r: Int): Int = c.registers()[r]!!

    @Test
    fun initSequence() {
        val c = mock(0xB5)
        Apds9250Full(c)
        val seq = c.writes().filter { it.size > 1 }.map { listOf(it[0].toInt() and 0xFF, it[1].toInt() and 0xFF) }
        assertEquals(listOf(listOf(0x04, 0x22), listOf(0x05, 0x01), listOf(0x19, 0x10), listOf(0x00, 0x06)), seq)
    }

    @Test
    fun rejectsWrongPart() {
        for (part in intArrayOf(0x00, 0xC5)) {
            val c = mock(part)
            assertThrows(IllegalStateException::class.java) { Apds9250Minimal(c) }
            assertTrue(c.writes().all { it.size == 1 })
        }
    }

    @Test
    fun readDecodesChannels() {
        val c = mock(0xB5)
        val chip = Apds9250Full(c)
        c.setRegister(0x0A, 0x01, 0x02, 0x03, 0x10, 0x00, 0x00, 0xFF, 0xFF, 0x0F, 0x00, 0x00, 0xF4)
        val n = chip.read()
        assertEquals(0x030201, n.ir)
        assertEquals(16, n.green)
        assertEquals(0xFFFFF, n.blue)
        assertEquals(0x40000, n.red)
        assertEquals(n, chip.readChannels())
        assertEquals(0x40000, chip.readRed())
        assertEquals(16, chip.readGreen())
        assertEquals(0xFFFFF, chip.readBlue())
        assertEquals(0x030201, chip.readIr())
    }

    @Test
    fun configuration() {
        val c = mock(0xB5)
        val chip = Apds9250Full(c)
        chip.setResolution(20)
        assertEquals(0x02, reg(c, 0x04))
        assertEquals(20, chip.getResolution())
        chip.setResolution(13)
        assertEquals(0x52, reg(c, 0x04))
        assertEquals(0.003125, chip.integrationTime(), 1e-9)
        chip.setResolution(16)
        chip.setMeasurementRate(2000)
        assertEquals(0x46, reg(c, 0x04))
        assertEquals(2000, chip.getMeasurementRate())
        chip.setMeasurementRate(25)
        assertEquals(0x40, reg(c, 0x04))
        for ((i, g) in intArrayOf(1, 3, 6, 9, 18).withIndex()) {
            chip.setGain(g)
            assertEquals(i, reg(c, 0x05))
            assertEquals(g, chip.getGain())
        }
        chip.setResolution(18)
        chip.setGain(3)
        assertEquals(10000.0, chip.normalized(3000), 1e-6)
    }

    @Test
    fun invalidArgumentsWriteNothing() {
        val c = mock(0xB5)
        val chip = Apds9250Full(c)
        val before = c.writes().size
        assertThrows(IllegalArgumentException::class.java) { chip.setGain(2) }
        assertThrows(IllegalArgumentException::class.java) { chip.setResolution(12) }
        assertThrows(IllegalArgumentException::class.java) { chip.setMeasurementRate(30) }
        assertThrows(IllegalArgumentException::class.java) { chip.setPersistence(0) }
        assertThrows(IllegalArgumentException::class.java) { chip.setPersistence(17) }
        assertThrows(IllegalArgumentException::class.java) { chip.setVariance(12) }
        assertThrows(IllegalArgumentException::class.java) { chip.setInterruptChannel(4) }
        assertThrows(IllegalArgumentException::class.java) { chip.setThresholds(5, 4) }
        assertThrows(IllegalArgumentException::class.java) { chip.setThresholds(0, 0x100000) }
        assertEquals(before, c.writes().size)
    }

    @Test
    fun modeEnableDisableReset() {
        val c = mock(0xB5)
        val chip = Apds9250Full(c)
        c.setRegister(0x00, 0x06)
        chip.setMode(false)
        assertEquals(0x02, reg(c, 0x00))
        chip.setMode(true)
        assertEquals(0x06, reg(c, 0x00))
        chip.disable()
        assertEquals(0x04, reg(c, 0x00))
        chip.enable()
        assertEquals(0x06, reg(c, 0x00))
        chip.softReset()
        val last = c.writes().last()
        assertEquals(2, last.size)
        assertEquals(0x10, last[1].toInt())
        assertEquals(Pair(0xB, 0x5), chip.partId())
    }

    @Test
    fun thresholds() {
        val c = mock(0xB5)
        val chip = Apds9250Full(c)
        chip.setThresholds(0x12345, 0xABCDE)
        assertEquals(listOf(0x45, 0x23, 0x01), listOf(reg(c, 0x24), reg(c, 0x25), reg(c, 0x26)))
        assertEquals(listOf(0xDE, 0xBC, 0x0A), listOf(reg(c, 0x21), reg(c, 0x22), reg(c, 0x23)))
        assertEquals(Pair(0x12345, 0xABCDE), chip.getThresholds())
    }

    @Test
    fun interruptConfiguration() {
        val c = mock(0xB5)
        val chip = Apds9250Full(c)
        chip.setInterruptChannel(Apds9250Full.CHANNEL_RED)
        assertEquals(0x20, reg(c, 0x19))
        chip.setInterruptChannel(Apds9250Full.CHANNEL_BLUE)
        chip.enableInterrupt(Apds9250Full.SOURCE_VARIATION)
        assertEquals(0x3C, reg(c, 0x19))
        chip.enableInterrupt(Apds9250Full.SOURCE_THRESHOLD)
        assertEquals(0x34, reg(c, 0x19))
        chip.disableInterrupt()
        assertEquals(0x30, reg(c, 0x19))
        chip.setPersistence(16)
        assertEquals(0xF0, reg(c, 0x1A))
        chip.setVariance(1024)
        assertEquals(7, reg(c, 0x27))
    }

    @Test
    fun statusKeepsInterruptBitAcrossDataReady() {
        val c = mock(0xB5)
        val chip = Apds9250Full(c)
        c.setRegister(0x07, 0x18)
        assertTrue(chip.dataReady())
        c.setRegister(0x07, 0x00)
        assertFalse(chip.dataReady())
        assertEquals(0x10, chip.pollInterrupt())
        assertEquals(0x00, chip.pollInterrupt())
        c.setRegister(0x07, 0x20)
        assertEquals(0x20, chip.status())
    }

    @Test
    fun callbackReceivesStatus() {
        val c = mock(0xB5)
        val chip = Apds9250Full(c)
        val calls = AtomicInteger()
        val last = AtomicInteger()
        c.setRegister(0x07, 0x10)
        chip.onInterrupt { s -> calls.incrementAndGet(); last.set(s) }
        val end = System.currentTimeMillis() + 2000
        while (calls.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        c.setRegister(0x07, 0x00)
        chip.offInterrupt()
        assertTrue(calls.get() >= 1)
        assertEquals(0x10, last.get())
    }
}
