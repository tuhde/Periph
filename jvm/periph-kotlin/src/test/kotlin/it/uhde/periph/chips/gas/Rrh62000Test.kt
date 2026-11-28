package it.uhde.periph.chips.gas

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Rrh62000Test {

    companion object {
        val WORDS = intArrayOf(1234, 1000, 500, 100, 20, 52, 125, 203, 61, 140, 230, 2345, 4567, 30, 812, 215, 0)

        fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        fun block(status: Int, words: IntArray): IntArray {
            val r = ByteArray(37)
            r[0] = status.toByte()
            r[1] = (status shr 8).toByte()
            for (i in 0 until 17) {
                r[2 + 2 * i] = (words[i] shr 8).toByte()
                r[3 + 2 * i] = words[i].toByte()
            }
            r[36] = Rrh62000Minimal.crc8(r, 36).toByte()
            return IntArray(37) { r[it].toInt() and 0xFF }
        }

        fun mock() = MockConnection().apply { setRegister(0x75, 2, 7) }

        fun full(c: MockConnection) = Rrh62000Full(c, 0).apply { init() }

        fun last(c: MockConnection): ByteArray = c.writes().last()
    }

    @Test
    fun crc() {
        assertEquals(0x92, Rrh62000Minimal.crc8(b(0xBE, 0xEF)))
        assertEquals(0x81, Rrh62000Minimal.crc8(b(0, 0)))
    }

    @Test
    fun initAndRead() {
        val c = mock()
        val s = Rrh62000Minimal(c, 0)
        s.init()
        assertArrayEquals(b(0x75), last(c))
        c.setRegister(0x40, 1)
        c.setRegister(0x00, *block(5, WORDS))
        val m = s.read()
        assertEquals(5.2, m.pm1UgM3, 1e-9)
        assertEquals(12.5, m.pm2_5UgM3, 1e-9)
        assertEquals(20.3, m.pm10UgM3, 1e-9)
        assertEquals(23.45, m.temperatureC, 1e-9)
        assertEquals(45.67, m.humidityRh, 1e-9)
        assertEquals(300, m.tvoc)
        assertEquals(812, m.eco2Ppm)
        assertEquals(2.15, m.iaq, 1e-9)
        val n = c.writes().size
        assertArrayEquals(b(0x40), c.writes()[n - 2])
        assertArrayEquals(b(0x00), c.writes()[n - 1])
    }

    @Test
    fun fullBlock() {
        val c = mock()
        val s = full(c)
        c.setRegister(0x40, 1)
        c.setRegister(0x00, *block(5, WORDS))
        val d = s.readAll()
        assertEquals(Rrh62000Status(5, true, false, true, false), d.status)
        assertEquals(123.4, d.particles.nc0_3, 1e-9)
        assertEquals(2.0, d.particles.nc4, 1e-9)
        assertEquals(6.1, d.particles.pm1SmokeUgM3, 1e-9)
        assertEquals(23.0, d.particles.pm10SmokeUgM3, 1e-9)
        val neg = IntArray(17).also { it[11] = 0xFE00 }
        c.setRegister(0x00, *block(2, neg))
        val before = c.writes().size
        val st = s.readStatus()
        assertTrue(st.dustAccumulation && !st.highConcentration && st.raw == 2)
        assertEquals(before + 1, c.writes().size)
        assertEquals(-5.12, s.readAll().temperatureC, 1e-9)
        c.setRegister(0x00, *block(0, WORDS))
        val p = s.readParticles()
        assertEquals(14.0, p.pm2_5SmokeUgM3, 1e-9)
        assertEquals(50.0, p.nc1, 1e-9)
    }

    @Test
    fun errors() {
        val c = mock()
        val s = full(c)
        c.setRegister(0x40, 1)
        val bad = block(0, WORDS).also { it[36] = it[36] xor 0xFF }
        c.setRegister(0x00, *bad)
        assertThrows(Rrh62000Exception::class.java) { s.read() }
        c.setRegister(0x00, *IntArray(37) { 0xFF })
        val e = assertThrows(Rrh62000Exception::class.java) { s.read() }
        assertTrue(e.message!!.contains("start-up"))
        c.setRegister(0x40, 0)
        c.setRegister(0x00, *block(0, WORDS))
        s.dataTimeoutMs = 0
        assertThrows(Rrh62000Exception::class.java) { s.read() }
    }

    @Test
    fun commands() {
        val c = mock()
        val s = full(c)
        c.setRegister(0x40, 0)
        assertFalse(s.dataReady())
        c.setRegister(0x40, 1)
        assertTrue(s.dataReady())
        s.sleep()
        assertArrayEquals(b(0x50, 0x00), last(c))
        s.wake()
        assertArrayEquals(b(0x50, 0x80), last(c))
        s.startCleaning()
        assertArrayEquals(b(0x51, 0x01), last(c))
        c.setRegister(0x74, 1)
        assertTrue(s.cleaningDone())
        s.softReset()
        assertArrayEquals(b(0x52, 0x81), last(c))
        c.failNextWrites(1, 0)
        assertDoesNotThrow { s.softReset() }
    }

    @Test
    fun settings() {
        val c = mock()
        val s = full(c)
        s.setMovingAverage(30)
        assertEquals(30, s.getMovingAverage())
        s.setFanSpeed(70)
        assertEquals(70, s.getFanSpeed())
        s.setCleaningInterval(86400)
        assertEquals(0x0B, c.registers()[0x5A])
        assertEquals(0x40, c.registers()[0x5B])
        assertEquals(86400, s.getCleaningInterval())
        s.setCleaningTime(20)
        assertEquals(20, s.getCleaningTime())
        assertThrows(IllegalArgumentException::class.java) { s.setMovingAverage(0) }
        assertThrows(IllegalArgumentException::class.java) { s.setMovingAverage(61) }
        assertThrows(IllegalArgumentException::class.java) { s.setFanSpeed(59) }
        assertThrows(IllegalArgumentException::class.java) { s.setFanSpeed(101) }
        assertThrows(IllegalArgumentException::class.java) { s.setCleaningInterval(31) }
        assertThrows(IllegalArgumentException::class.java) { s.setCleaningInterval(1814430) }
        assertThrows(IllegalArgumentException::class.java) { s.setCleaningTime(61) }
        c.setRegister(0x71, 0x00, 0x01, 0xE2, 0x40)
        assertEquals(123456L, s.readMoxResistance())
        c.setRegister(0x72, 1, 2, 3, 4, 5, 6)
        assertArrayEquals(b(1, 2, 3, 4, 5, 6), s.uniqueId())
        c.setRegister(0x73, 3, 1, 4)
        assertArrayEquals(intArrayOf(3, 1, 4), s.algorithmVersion())
        c.setRegister(0x75, 2, 7)
        assertArrayEquals(intArrayOf(2, 7), s.firmwareVersion())
    }
}
