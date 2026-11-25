package it.uhde.periph.chips.light

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class MAX44009Test {

    companion object {
        @BeforeAll
        @JvmStatic
        fun noInitWait() {
            MAX44009Minimal.initWaitMs = 0   // skip the 900 ms first-conversion wait
        }
    }

    private fun mock(high: Int, low: Int): MockConnection {
        val c = MockConnection()
        c.setRegister(0x02, 0x03)
        c.setRegister(0x03, high)
        c.setRegister(0x04, low)
        return c
    }

    private fun reg(c: MockConnection, r: Int): Int = c.registers()[r]!!

    @Test
    fun initSequence() {
        val c = mock(0, 0)
        MAX44009Full(c)
        val seq = c.writes().filter { it.size > 1 }.map { listOf(it[0].toInt() and 0xFF, it[1].toInt() and 0xFF) }
        assertEquals(listOf(listOf(1, 0), listOf(2, 3), listOf(5, 255), listOf(6, 0), listOf(7, 255)), seq)
        assertTrue(c.writes().any { it.size == 1 && it[0].toInt() == 0x00 })
    }

    @Test
    fun luxConversion() {
        val c = mock(0, 0)
        val chip = MAX44009Full(c)
        assertEquals(0.0, chip.lux(), 0.0)
        c.setRegister(0x03, 0x2A); c.setRegister(0x04, 0x05)
        assertEquals(4 * 0xA5 * 0.045, chip.lux(), 1e-9)
        c.setRegister(0x03, 0xEF); c.setRegister(0x04, 0x0F)
        assertEquals(188006.4, chip.lux(), 1e-6)
        c.setRegister(0x03, 0xF3)
        assertEquals(188006.4, chip.lux(), 1e-6)
        c.setRegister(0x03, 0x01); c.setRegister(0x04, 0xF7)
        assertEquals(0x17 * 0.045, chip.lux(), 1e-9)
    }

    @Test
    fun rawAndResolution() {
        val c = mock(0x5B, 0x0C)
        val chip = MAX44009Full(c)
        val raw = chip.readRaw()
        assertEquals(MAX44009Full.Raw(5, 0xBC, false), raw)
        assertEquals(32 * 0.045, chip.resolution(), 1e-9)
        assertFalse(chip.isOverrange())
        c.setRegister(0x03, 0xF0)
        assertTrue(chip.readRaw().overrange)
        assertTrue(chip.isOverrange())
    }

    @Test
    fun configuration() {
        val c = mock(0, 0)
        val chip = MAX44009Full(c)
        chip.setContinuous(true)
        assertEquals(0x83, reg(c, 2))
        chip.setContinuous(false)
        assertEquals(0x03, reg(c, 2))
        chip.setManual(50.0)
        assertEquals(0x44, reg(c, 2))
        chip.setManual(12.0, true)
        assertEquals(0x40 or 0x08 or 0x06, reg(c, 2))
        chip.setContinuous(true)
        chip.setManual(1000.0)
        assertEquals(0xC0, reg(c, 2))
        chip.setAutomatic()
        assertEquals(0, reg(c, 2) and 0x40)
        assertTrue(reg(c, 2) and 0x80 != 0)
        c.setRegister(0x02, 0xCD)
        assertEquals(MAX44009Full.Config(true, true, true, 25.0), chip.getConfig())
    }

    @Test
    fun thresholdsAndDelay() {
        val c = mock(0, 0)
        val chip = MAX44009Full(c)
        chip.setThresholds(0.0, 188006.4)
        assertEquals(0x00, reg(c, 6))
        assertEquals(0xEF, reg(c, 5))
        chip.setThresholds(5.0, 100.0)
        assertEquals(0x07, reg(c, 6))
        assertEquals(4, reg(c, 5) shr 4)
        assertTrue(reg(c, 5) and 0x08 != 0)
        val (lo, hi) = chip.getThresholds()
        assertEquals(5.0, lo, 0.5)
        assertEquals(100.0, hi, 13.0)
        assertEquals(4 * 0x5F * 0.045, MAX44009Full.decodeThreshold(0x25, true), 1e-9)
        assertEquals(4 * 0x50 * 0.045, MAX44009Full.decodeThreshold(0x25, false), 1e-9)
        assertThrows(IllegalArgumentException::class.java) { chip.setThresholds(10.0, 5.0) }
        chip.setThresholdDelay(2.0)
        assertEquals(20, reg(c, 7))
        assertEquals(2.0, chip.getThresholdDelay(), 1e-9)
        chip.setThresholdDelay(99.0)
        assertEquals(255, reg(c, 7))
        chip.setThresholdDelay(-1.0)
        assertEquals(0, reg(c, 7))
    }

    @Test
    fun interrupt() {
        val c = mock(0, 0)
        val chip = MAX44009Full(c)
        chip.enableInterrupt()
        assertEquals(0x01, reg(c, 1))
        chip.disableInterrupt()
        assertEquals(0x00, reg(c, 1))
        c.setRegister(0x00, 0x01)
        assertEquals(1, chip.pollInterrupt())
        c.setRegister(0x00, 0x00)
        assertEquals(0, chip.pollInterrupt())

        val calls = AtomicInteger()
        val last = AtomicInteger()
        c.setRegister(0x00, 0x01)
        chip.onInterrupt { s -> calls.incrementAndGet(); last.set(s) }
        val end = System.currentTimeMillis() + 2000
        while (calls.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        c.setRegister(0x00, 0x00)
        chip.offInterrupt()
        assertTrue(calls.get() >= 1)
        assertEquals(1, last.get())
    }
}
