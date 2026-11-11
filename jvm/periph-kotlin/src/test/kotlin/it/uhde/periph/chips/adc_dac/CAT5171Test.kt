package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CAT5171Test {

    private fun last(c: MockConnection): ByteArray = c.writes().last()

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun withReads(vararg values: Int): MockConnection {
        val c = MockConnection()
        for (v in values) c.queueRead(b(v))
        return c
    }

    @Test
    fun constructionMakesNoBusTraffic() {
        val c = MockConnection()
        CAT5171Minimal(c)
        assertEquals(0, c.writes().size)
    }

    @Test
    fun setWiperEncodesInstructionAndClamps() {
        val c = MockConnection()
        val pot = CAT5171Minimal(c)
        pot.setWiper(100)
        assertArrayEquals(b(0x00, 100), last(c))
        pot.setWiper(300)
        assertArrayEquals(b(0x00, 255), last(c))
        pot.setWiper(-5)
        assertArrayEquals(b(0x00, 0), last(c))
    }

    @Test
    fun getWiperIsBareRead() {
        val c = withReads(17)
        val pot = CAT5171Minimal(c)
        assertEquals(17, pot.getWiper())
        assertEquals(0, c.writes().size)
    }

    @Test
    fun resistanceConversions() {
        val c = MockConnection()
        val pot = CAT5171Minimal(c)
        pot.setResistance(50050.0)
        assertArrayEquals(b(0x00, 128), last(c))
        pot.setResistance(0.0)
        assertArrayEquals(b(0x00, 0), last(c))
        pot.setResistance(1e7)
        assertArrayEquals(b(0x00, 255), last(c))
        val pot2 = CAT5171Minimal(withReads(128, 0))
        assertEquals(50050.0, pot2.getResistance(), 0.01)
        assertEquals(50.0, pot2.getResistance(), 0.01)
    }

    @Test
    fun fullHelpers() {
        val c = MockConnection()
        val pot = CAT5171Full(c)
        pot.setRatio(0.25)
        assertArrayEquals(b(0x00, 64), last(c))
        pot.setRatio(1.0)
        assertArrayEquals(b(0x00, 255), last(c))
        pot.setResistanceWa(50050.0)
        assertArrayEquals(b(0x00, 128), last(c))

        assertEquals(0.5, CAT5171Full(withReads(128)).getRatio(), 1e-9)
        assertEquals(75050.0, CAT5171Full(withReads(64)).getResistanceWa(), 0.01)

        val i = withReads(250, 250, 128)
        val inc = CAT5171Full(i)
        assertEquals(253, inc.increment(3))
        assertArrayEquals(b(0x00, 253), last(i))
        assertEquals(255, inc.increment(100))
        assertEquals(100, inc.increment(-28))
        assertArrayEquals(b(0x00, 100), last(i))
    }

    @Test
    fun shutdownMidscaleReset() {
        val c = MockConnection()
        val pot = CAT5171Full(c)
        assertFalse(pot.isShutdown())
        pot.shutdown()
        assertArrayEquals(b(0x20, 128), last(c))
        assertTrue(pot.isShutdown())
        pot.resume()
        assertArrayEquals(b(0x00, 128), last(c))
        assertFalse(pot.isShutdown())
        pot.setWiper(77)
        pot.shutdown()
        assertArrayEquals(b(0x20, 77), last(c))
        pot.setWiper(50)
        assertArrayEquals(b(0x20, 50), last(c))
        pot.resume()
        assertArrayEquals(b(0x00, 50), last(c))
        pot.midscale()
        assertArrayEquals(b(0x40, 0x80), last(c))
        pot.shutdown()
        pot.midscale()
        assertArrayEquals(b(0x60, 0x80), last(c))
        assertTrue(pot.isShutdown())
        pot.reset()
        assertArrayEquals(b(0x40, 0x80), last(c))
        assertFalse(pot.isShutdown())
    }

    @Test
    fun setRabChangesConversionOnly() {
        val c = withReads(128, 128)
        val pot = CAT5171Full(c)
        pot.setRab(50000.0, 100.0)
        assertEquals(0, c.writes().size)
        assertEquals(25100.0, pot.getResistance(), 0.01)
        pot.setRab(50000.0)
        assertEquals(25100.0, pot.getResistance(), 0.01)
    }
}
