package it.uhde.periph.chips.other

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class PCA9541Test {

    /** Recomputes the read-only N bits of CONTROL after a write (this master's view). */
    class SimMock : MockConnection() {
        override fun write(data: ByteArray) {
            val prior = registers()[0x01] ?: 0
            super.write(data)
            if (data.size == 2 && data[0].toInt() == 0x01) {
                val w = data[1].toInt() and 0xFF
                val mybus = w and 1
                val buson = (w shr 2) and 1
                val nmybusPrior = (prior shr 1) and 1
                val nbusonPrior = (prior shr 3) and 1
                val control = mybus == nmybusPrior
                val on = buson != nbusonPrior
                val nmybus = if (control) mybus else 1 - mybus
                val nbuson = if (on) 1 - buson else buson
                setRegister(0x01, (w and 0xF5) or (nmybus shl 1) or (nbuson shl 3))
            }
        }
    }

    private fun mock(control: Int, ie: Int = 0, istat: Int = 0): MockConnection {
        val c = SimMock()
        c.setRegister(0x00, ie); c.setRegister(0x01, control); c.setRegister(0x02, istat)
        c.setRegister(0x10, ie); c.setRegister(0x11, control); c.setRegister(0x12, istat)
        return c
    }

    private fun reg(c: MockConnection, r: Int): Int = c.registers()[r]!!
    private fun dataWrites(c: MockConnection) = c.writes().filter { it.size > 1 }
    private fun lastWrite(c: MockConnection): Int = dataWrites(c).last()[1].toInt() and 0xFF

    @Test
    fun initReadsAndWritesNothing() {
        val c = mock(0x04)
        PCA9541Full(c)
        assertEquals(0, dataWrites(c).size)
        assertEquals(listOf(0x01, 0x02), c.writes().map { it[0].toInt() })
        val bad = mock(0)
        bad.failNextWrites(1, 0)
        assertThrows(IOException::class.java) { PCA9541Minimal(bad) }
    }

    @Test
    fun acquireFollowsTable12() {
        val table = mapOf(0x0 to 0x4, 0x1 to 0x4, 0x2 to 0x5, 0x3 to 0x5, 0x5 to 0x4, 0x6 to 0x5,
            0x9 to 0x0, 0xA to 0x1, 0xC to 0x0, 0xD to 0x0, 0xE to 0x1, 0xF to 0x1)
        for ((read, want) in table) {
            val c = mock(read)
            PCA9541Full(c).acquire(false, 0)
            assertEquals(want, lastWrite(c), "CONTROL read $read")
        }
        for (read in listOf(0x4, 0x7, 0x8, 0xB)) assertTrue(PCA9541Full(mock(read)).acquire(false, 0), "keeps control $read")
    }

    @Test
    fun hasControlAndBusInit() {
        val cases = mapOf(0x04 to true, 0x0A to false, 0x00 to false, 0x02 to false, 0x07 to true, 0x05 to false)
        for ((c, v) in cases) assertEquals(v, PCA9541Full(mock(c)).hasControl(), "control $c")
        val c = mock(0x0A, 0, 0x02)
        assertTrue(PCA9541Full(c).acquire(true, 20))
        assertEquals(0x11, lastWrite(c))
        assertFalse(PCA9541Full(mock(0x0A, 0, 0x00)).acquire(true, 3))
        val r = mock(0x0A, 0, 0x02)
        assertTrue(PCA9541Full(r).recoverBus(20))
        assertTrue(lastWrite(r) and 0x10 != 0)
    }

    @Test
    fun releaseConnectDisconnect() {
        val c = mock(0x04)
        val chip = PCA9541Full(c)
        chip.release()
        assertEquals(0x05, lastWrite(c))
        assertFalse(chip.hasControl())

        val c2 = mock(0x0D)
        PCA9541Full(c2).connect()
        assertEquals(0x01, lastWrite(c2))

        val c3 = mock(0x04)
        val chip3 = PCA9541Full(c3)
        chip3.disconnect()
        assertEquals(0x00, lastWrite(c3))
        assertFalse(chip3.isConnected())
        chip3.connect()
        assertTrue(chip3.isConnected())
    }

    @Test
    fun statusAndReadAll() {
        val chip = PCA9541Full(mock(0xD4))
        assertEquals(0xD4, chip.getControl())
        assertEquals(PCA9541Full.Status(true, true, true, true, true), chip.status())

        val c = mock(0x04, 0x03, 0x08)
        val chip2 = PCA9541Full(c)
        val n = c.writes().size
        assertEquals(Triple(0x03, 0x04, 0x08), chip2.readAll())
        assertEquals(n + 1, c.writes().size)
        assertEquals(0x10, c.writes().last()[0].toInt())
    }

    @Test
    fun interruptMaskAndTest() {
        val c = mock(0x0D)
        val chip = PCA9541Full(c)
        chip.setInterruptMask(busLost = true, intIn = true)
        assertEquals(0x09, chip.getInterruptMask())
        chip.setInterruptMask()
        chip.disableInterrupt(PCA9541Full.SOURCE_BUS_INIT)
        assertEquals(0x02, reg(c, 0))
        chip.disableInterrupt(PCA9541Full.SOURCE_INT_IN)
        chip.enableInterrupt(PCA9541Full.SOURCE_BUS_INIT)
        assertEquals(0x01, reg(c, 0))
        val n = dataWrites(c).size
        chip.enableInterrupt(PCA9541Full.SOURCE_TEST)
        assertEquals(n, dataWrites(c).size)

        val bits = reg(c, 1) and 0x05
        chip.testInterrupt(own = true)
        assertEquals(bits or 0x40, reg(c, 1) and 0xC5)
        chip.testInterrupt(other = true)
        assertEquals(0x80, reg(c, 1) and 0xC0)
        chip.testInterrupt(own = true, other = true)
        assertEquals(0xC0, reg(c, 1) and 0xC0)
        chip.testInterrupt()
        assertEquals(0, reg(c, 1) and 0xC0)
        assertEquals(bits, reg(c, 1) and 0x05)
    }

    @Test
    fun pollAndCallback() {
        val c = mock(0x04, 0, 0x0A)
        val chip = PCA9541Full(c)
        assertEquals(0x0A, chip.pollInterrupt())

        val calls = AtomicInteger()
        val last = AtomicInteger()
        chip.onInterrupt { s -> calls.incrementAndGet(); last.set(s) }
        val end = System.currentTimeMillis() + 2000
        while (calls.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        c.setRegister(0x02, 0x00)
        chip.offInterrupt()
        assertTrue(calls.get() >= 1)
        assertEquals(0x0A, last.get())
    }
}
