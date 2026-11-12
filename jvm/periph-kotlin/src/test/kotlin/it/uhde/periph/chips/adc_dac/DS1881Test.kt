package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class DS1881Test {

    private fun last(c: MockConnection): ByteArray = c.writes().last()

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun initWritesConfigOnlyWhenDifferent() {
        val c = MockConnection()
        val pot = DS1881Minimal(c)
        assertEquals(0, c.writes().size)
        pot.init()
        assertEquals(1, c.writes().size)
        assertArrayEquals(b(0x86), last(c))

        val c2 = MockConnection()
        c2.queueRead(b(10, 20, 0x86))
        DS1881Minimal(c2).init()
        assertEquals(0, c2.writes().size)

        val c3 = MockConnection()
        c3.queueRead(b(0, 0, 0xB6))
        DS1881Minimal(c3).init()
        assertEquals(0, c3.writes().size)
    }

    @Test
    fun attenuationEncodingRoundingAndClamping() {
        val c = MockConnection()
        val pot = DS1881Minimal(c)
        pot.setAttenuation(0, 20.0)
        assertArrayEquals(b(20), last(c))
        pot.setAttenuation(1, 20.4)
        assertArrayEquals(b(0x40 or 20), last(c))
        pot.setAttenuation(1, 20.6)
        assertArrayEquals(b(0x40 or 21), last(c))
        pot.setAttenuation(0, 100.0)
        assertArrayEquals(b(62), last(c))
        pot.setAttenuation(0, -5.0)
        assertArrayEquals(b(0), last(c))
        assertThrows(IllegalArgumentException::class.java) { pot.setAttenuation(2, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { pot.getPosition(-1) }
        pot.mute(1)
        assertArrayEquals(b(0x7F), last(c))
        pot.setPosition(0, 99)
        assertArrayEquals(b(63), last(c))
        pot.setPosition(0, -3)
        assertArrayEquals(b(0), last(c))
    }

    @Test
    fun readsMaskUpperBits() {
        val c = MockConnection()
        repeat(4) { c.queueRead(b(0xC5, 0x3F, 0x86)) }
        val pot = DS1881Minimal(c)
        assertEquals(5, pot.getPosition(0))
        assertEquals(63, pot.getPosition(1))
        assertEquals(80.0, pot.getAttenuation(1))
        assertEquals(5.0, pot.getAttenuation(0))
        assertEquals(0, c.writes().size)
    }

    @Test
    fun stereoWriteIsOneTransaction() {
        val c = MockConnection()
        val full = DS1881Full(c)
        full.setBoth(10, 99)
        assertEquals(1, c.writes().size)
        assertArrayEquals(b(10, 0x40 or 63), last(c))
        full.setAttenuationBoth(30.0)
        assertArrayEquals(b(30, 0x40 or 30), last(c))
        full.setAttenuationBoth(5.0, 70.0)
        assertArrayEquals(b(5, 0x7F), last(c))
    }

    @Test
    fun option1And2Tables() {
        val c = MockConnection()
        val full = DS1881Full(c)
        assertEquals(0.0, full.positionToDb(0))
        assertEquals(62.0, full.positionToDb(62))
        assertEquals(80.0, full.positionToDb(63))
        assertEquals(62, full.maxPosition())
        assertEquals(12, full.dbToPosition(12.4))
        assertEquals(63, full.dbToPosition(75.0))

        full.configure(2, true, false)
        assertArrayEquals(b(0x87), last(c))
        val n = c.writes().size
        full.configure(2, true, false)
        assertEquals(n, c.writes().size)
        val want = doubleArrayOf(0.0, 12.0, 14.0, 36.0, 39.0, 60.0, 80.0)
        val pos = intArrayOf(0, 12, 13, 24, 25, 32, 33)
        for (i in pos.indices) assertEquals(want[i], full.positionToDb(pos[i]))
        assertEquals(32, full.maxPosition())
        assertEquals(13, full.dbToPosition(14.0))
        assertEquals(24, full.dbToPosition(36.0))
        assertEquals(32, full.dbToPosition(60.0))
        assertEquals(24, full.dbToPosition(37.0))
        assertEquals(25, full.dbToPosition(38.0))
        assertEquals(33, full.dbToPosition(70.0))
        full.setBoth(40, 40)
        assertArrayEquals(b(33, 0x40 or 33), last(c))
        full.mute(0)
        assertArrayEquals(b(33), last(c))
        assertThrows(IllegalArgumentException::class.java) { full.configure(3, true, false) }
    }

    @Test
    fun configBitsAndNonvolatilePolling() {
        val c = MockConnection()
        val full = DS1881Full(c)
        full.setZeroCrossing(false)
        assertArrayEquals(b(0x84), last(c))
        full.setNonvolatile(true)
        assertArrayEquals(b(0x80), last(c))
        full.setOption(2)
        assertArrayEquals(b(0x81), last(c))
        c.failNextReads(1)
        full.setBoth(1, 2)
        assertArrayEquals(b(1, 0x42), last(c))
    }

    @Test
    fun getConfigAndReadAll() {
        val c = MockConnection()
        c.queueRead(b(0, 0, 0x87))
        c.queueRead(b(1, 2, 0x82))
        c.queueRead(b(0x41, 0x02, 0x86))
        val full = DS1881Full(c)
        var cfg = full.getConfig()
        assertEquals(2, cfg.option)
        assertTrue(cfg.zeroCrossing)
        assertFalse(cfg.nonvolatile)
        assertEquals(0x87, cfg.raw)
        assertEquals(32, full.maxPosition())
        cfg = full.getConfig()
        assertTrue(cfg.nonvolatile)
        assertEquals(1, cfg.option)
        assertArrayEquals(intArrayOf(1, 2, 0x86), full.readAll())
    }

    @Test
    fun waitReadyRecoversAndTimesOut() {
        val c = MockConnection()
        val full = DS1881Full(c)
        c.failNextReads(3)
        full.waitReady(0.05)
        c.failNextReads(1000)
        assertThrows(IOException::class.java) { full.waitReady(0.01) }
    }

    @Test
    fun storeAndReset() {
        val c = MockConnection()
        c.queueRead(b(12, 34, 0x86))
        val full = DS1881Full(c)
        full.store()
        val w = c.writes()
        assertEquals(3, w.size)
        assertArrayEquals(b(0x82), w[0])
        assertArrayEquals(b(12, 0x40 or 34), w[1])
        assertArrayEquals(b(0x86), w[2])

        val c2 = MockConnection()
        val f2 = DS1881Full(c2)
        f2.configure(2, false, true)
        f2.reset()
        assertArrayEquals(b(0x86, 0x3F, 0x7F), last(c2))
        assertEquals(62, f2.maxPosition())
    }
}
