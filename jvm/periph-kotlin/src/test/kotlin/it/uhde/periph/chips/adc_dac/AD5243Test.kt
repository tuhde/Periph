package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AD5243Test {

    private fun last(c: MockConnection): ByteArray = c.writes().last()

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun constructionMakesNoBusTraffic() {
        val c = MockConnection()
        AD5243Minimal(c)
        assertEquals(0, c.writes().size)
    }

    @Test
    fun setWiperEncodesInstructionAndClamps() {
        val c = MockConnection()
        val pot = AD5243Minimal(c)
        pot.setWiper(0, 100)
        assertArrayEquals(b(0x00, 100), last(c))
        pot.setWiper(1, 200)
        assertArrayEquals(b(0x80, 200), last(c))
        pot.setWiper(0, 300)
        assertArrayEquals(b(0x00, 255), last(c))
        pot.setWiper(0, -5)
        assertArrayEquals(b(0x00, 0), last(c))
        assertThrows(IllegalArgumentException::class.java) { pot.setWiper(2, 1) }
    }

    @Test
    fun getWiperSelectsChannelByInstruction() {
        val c = MockConnection()
        c.setRegister(0x00, 17)
        c.setRegister(0x80, 99)
        val pot = AD5243Minimal(c)
        assertEquals(17, pot.getWiper(0))
        assertEquals(99, pot.getWiper(1))
    }

    @Test
    fun resistanceConversions() {
        val c = MockConnection()
        val pot = AD5243Minimal(c)
        pot.setResistance(0, 5320.0)
        assertArrayEquals(b(0x00, 128), last(c))
        pot.setResistance(0, 0.0)
        assertArrayEquals(b(0x00, 0), last(c))
        pot.setResistance(1, 1e6)
        assertArrayEquals(b(0x80, 255), last(c))
        c.setRegister(0x00, 128)
        c.setRegister(0x80, 0)
        assertEquals(5320.0, pot.getResistance(0), 0.01)
        assertEquals(320.0, pot.getResistance(1), 0.01)
    }

    @Test
    fun fullHelpers() {
        val c = MockConnection()
        val pot = AD5243Full(c)
        pot.setBoth(1, 2)
        assertArrayEquals(b(0x00, 1), c.writes()[0])
        assertArrayEquals(b(0x80, 2), c.writes()[1])
        pot.setRatio(0, 0.25)
        assertArrayEquals(b(0x00, 64), last(c))
        pot.setRatio(0, 1.0)
        assertArrayEquals(b(0x00, 255), last(c))
        pot.setResistanceWa(1, 5320.0)
        assertArrayEquals(b(0x80, 128), last(c))

        c.setRegister(0x00, 250)
        c.setRegister(0x80, 128)
        assertEquals(0.5, pot.getRatio(1), 1e-9)
        assertEquals(5320.0, pot.getResistanceWa(1), 0.01)
        assertEquals(253, pot.increment(0, 3))
        assertArrayEquals(b(0x00, 253), last(c))
        assertEquals(255, pot.increment(0, 100))
        assertEquals(100, pot.increment(1, -28))
    }

    @Test
    fun shutdownResumeReset() {
        val c = MockConnection()
        val pot = AD5243Full(c)
        pot.setWiper(1, 77)
        assertFalse(pot.isShutdown(1))
        pot.shutdown(1)
        assertArrayEquals(b(0xC0, 77), last(c))
        assertTrue(pot.isShutdown(1))
        assertFalse(pot.isShutdown(0))
        pot.setWiper(1, 50)
        assertArrayEquals(b(0xC0, 50), last(c))
        pot.resume(1)
        assertArrayEquals(b(0x80, 50), last(c))
        assertFalse(pot.isShutdown(1))
        pot.shutdown(0)
        assertArrayEquals(b(0x40, 128), last(c))
        pot.reset()
        val w = c.writes()
        assertArrayEquals(b(0x00, 128), w[w.size - 2])
        assertArrayEquals(b(0x80, 128), w[w.size - 1])
        assertFalse(pot.isShutdown(0))
    }

    @Test
    fun setRabChangesConversionOnly() {
        val c = MockConnection()
        c.setRegister(0x00, 128)
        val pot = AD5243Full(c)
        pot.setRab(50000.0, 100.0)
        assertEquals(0, c.writes().size)
        assertEquals(25200.0, pot.getResistance(0), 0.01)
        pot.setRab(10000.0)
        assertEquals(5200.0, pot.getResistance(0), 0.01)
    }
}
