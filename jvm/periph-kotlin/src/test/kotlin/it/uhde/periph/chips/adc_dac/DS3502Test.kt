package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class DS3502Test {

    private fun mock(wr: Int): MockConnection {
        val c = MockConnection()
        c.setRegister(0x00, wr)
        c.setRegister(0x02, 0x00)
        return c
    }

    private fun wr(c: MockConnection) = c.registers()[0x00]
    private fun cr(c: MockConnection) = c.registers()[0x02]
    private fun dataWrites(c: MockConnection) = c.writes().filter { it.size > 1 }

    @Test
    fun initSetsModeAndKeepsWiper() {
        val c = mock(0x23)
        val pot = DS3502Full(c)
        assertEquals(0x80, cr(c))
        assertEquals(1, dataWrites(c).size)
        assertEquals(0x02, dataWrites(c)[0][0].toInt())
        assertEquals(0x23, wr(c))
        assertEquals(0x23, pot.getWiper())
    }

    @Test
    fun initRetriesOnceAfterNack() {
        val c = mock(0x40)
        c.failNextWrites(1, 0)
        DS3502Minimal(c)
        assertEquals(0x80, cr(c))
        assertEquals(2, dataWrites(c).size)

        val d = mock(0x40)
        d.failNextWrites(2, 0)
        assertThrows(IOException::class.java) { DS3502Minimal(d) }
    }

    @Test
    fun wiperAndVoltage() {
        val c = mock(0x40)
        val pot = DS3502Minimal(c, 15.0, 0.0)
        pot.setWiper(100)
        assertEquals(100, wr(c))
        pot.setWiper(300)
        assertEquals(127, wr(c))
        pot.setWiper(-5)
        assertEquals(0, wr(c))
        c.setRegister(0x00, 0xFF)
        assertEquals(127, pot.getWiper())
        pot.setVoltage(6.0)
        assertEquals(51, wr(c))
        assertEquals(15.0 * 51 / 127, pot.getVoltage(), 1e-9)
        pot.setVoltage(99.0)
        assertEquals(127, wr(c))
        pot.setVoltage(-3.0)
        assertEquals(0, wr(c))

        val c2 = mock(0x40)
        DS3502Minimal(c2, 12.0, 2.0).setVoltage(7.0)
        assertEquals(64, wr(c2))
        val c3 = mock(0x40)
        DS3502Minimal(c3, 5.0, 5.0).setVoltage(5.0)
        assertEquals(0, wr(c3))
    }

    @Test
    fun ratioResistanceIncrement() {
        val c = mock(0x40)
        val pot = DS3502Full(c)
        pot.setRatio(0.5)
        assertEquals(64, wr(c))
        pot.setRatio(2.0)
        assertEquals(127, wr(c))
        assertEquals(1.0, pot.getRatio(), 0.0)
        c.setRegister(0x00, 0)
        assertEquals(0.0, pot.getResistance('L'), 0.0)
        assertEquals(10000.0, pot.getResistance('H'), 0.0)
        c.setRegister(0x00, 127)
        assertEquals(10000.0, pot.getResistance(), 0.0)
        assertEquals(0.0, pot.getResistance('H'), 0.0)
        assertThrows(IllegalArgumentException::class.java) { pot.getResistance('X') }
        c.setRegister(0x00, 0x40)
        assertEquals(69, pot.increment(5))
        assertEquals(69, wr(c))
        assertEquals(59, pot.increment(-10))
        c.setRegister(0x00, 125)
        assertEquals(127, pot.increment(10))
        c.setRegister(0x00, 3)
        assertEquals(0, pot.increment(-10))
    }

    @Test
    fun modeAndStoreWiper() {
        val c = mock(0x40)
        val pot = DS3502Full(c)
        assertFalse(pot.getMode())
        pot.setMode(true)
        assertEquals(0x00, cr(c))
        assertTrue(pot.getMode())
        pot.setMode(false)
        assertEquals(0x80, cr(c))

        val n = dataWrites(c).size
        pot.storeWiper(77)
        val seq = dataWrites(c).drop(n)
        assertEquals(3, seq.size)
        assertEquals(listOf(0x02, 0x00), listOf(seq[0][0].toInt() and 0xFF, seq[0][1].toInt() and 0xFF))
        assertEquals(listOf(0x00, 77), listOf(seq[1][0].toInt() and 0xFF, seq[1][1].toInt() and 0xFF))
        assertEquals(listOf(0x02, 0x80), listOf(seq[2][0].toInt() and 0xFF, seq[2][1].toInt() and 0xFF))
        assertEquals(0x80, cr(c))
        assertEquals(77, pot.getWiper())
        c.setRegister(0x00, 33)
        pot.storeWiper()
        assertEquals(33, wr(c))
    }

    @Test
    fun storeWiperRestoresModeOnTimeout() {
        val c = mock(0x40)
        val pot = DS3502Full(c)
        c.failNextWrites(31, 2)   // CR <- 0 and WR <- code succeed; all 31 polls NACK
        assertThrows(IOException::class.java) { pot.storeWiper(10) }
        assertEquals(0x80, cr(c))
    }

    @Test
    fun waitReadyAndEndpoints() {
        val c = mock(0x40)
        val pot = DS3502Full(c)
        c.failNextWrites(3, 0)
        pot.waitReady()
        c.failNextWrites(1000, 0)
        assertThrows(IOException::class.java) { pot.waitReady(5) }
        c.failNextWrites(0, 0)
        val n = c.writes().size
        pot.setEndpoints(10.0)
        assertEquals(n, c.writes().size)
        c.setRegister(0x00, 127)
        assertEquals(10.0, pot.getVoltage(), 1e-9)
    }
}
