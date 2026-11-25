package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class SHT31Test {

    private fun frame(rawT: Int, rawRh: Int): ByteArray {
        val f = ByteArray(6)
        f[0] = (rawT shr 8).toByte(); f[1] = rawT.toByte()
        f[3] = (rawRh shr 8).toByte(); f[4] = rawRh.toByte()
        f[2] = SHT31Minimal.crc8(f, 0, 2).toByte()
        f[5] = SHT31Minimal.crc8(f, 3, 2).toByte()
        return f
    }

    private fun cmds(c: MockConnection) = c.writes().map { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }

    private lateinit var mock: MockConnection
    private lateinit var s: SHT31Full

    private fun make() {
        mock = MockConnection()
        s = SHT31Full(mock)
    }

    @Test
    fun crcReference() {
        assertEquals(0x92, SHT31Minimal.crc8(byteArrayOf(0xBE.toByte(), 0xEF.toByte()), 0, 2))
    }

    @Test
    fun initAndRead() {
        make()
        assertEquals(listOf(0x30A2), cmds(mock))
        mock.writes().clear()
        mock.queueRead(frame(0x6666, 0x8000))
        val v = s.read()
        assertEquals(listOf(0x2400), cmds(mock))
        assertEquals(-45.0 + 175.0 * 0x6666 / 65535.0, v[0], 1e-9)
        assertEquals(100.0 * 0x8000 / 65535.0, v[1], 1e-9)
        assertNotNull(SHT31Minimal(MockConnection()))
    }

    @Test
    fun readPollsAndRetries() {
        make()
        mock.failNextReads(3)
        mock.queueRead(frame(0, 65535))
        val v = s.read()
        assertEquals(-45.0, v[0], 1e-9)
        assertEquals(100.0, v[1], 1e-9)

        mock.writes().clear()
        val bad = frame(0x1234, 0x5678)
        bad[2] = (bad[2].toInt() xor 0xFF).toByte()
        mock.queueRead(bad)
        mock.queueRead(frame(0x1234, 0x5678))
        s.read()
        assertEquals(listOf(0x2400, 0x2400), cmds(mock))
        mock.queueRead(bad)
        mock.queueRead(bad)
        assertThrows(IOException::class.java) { s.read() }
    }

    @Test
    fun rawAndSingleValues() {
        make()
        mock.queueRead(frame(1000, 2000))
        assertArrayEquals(intArrayOf(1000, 2000), s.readRaw())
        mock.queueRead(frame(0, 0))
        assertEquals(-45.0, s.readTemperature(), 1e-9)
        mock.queueRead(frame(0, 65535))
        assertEquals(100.0, s.readHumidity(), 1e-9)
    }

    @Test
    fun repeatabilityAndStretching() {
        make()
        mock.writes().clear()
        s.setRepeatability("low")
        mock.queueRead(frame(0, 0)); s.read()
        s.setRepeatability("medium")
        mock.queueRead(frame(0, 0)); s.read()
        s.setClockStretching(true)
        mock.queueRead(frame(0, 0)); s.read()
        s.setRepeatability("high")
        mock.queueRead(frame(0, 0)); s.read()
        assertEquals(listOf(0x2416, 0x240B, 0x2C0D, 0x2C06), cmds(mock))
        assertThrows(IllegalArgumentException::class.java) { s.setRepeatability("ultra") }
    }

    @Test
    fun periodicMode() {
        make()
        mock.writes().clear()
        s.startPeriodic(2.0)
        s.startPeriodic(0.5, "low")
        s.startPeriodic(10.0, "medium")
        s.startPeriodic(1.0, "high", true)
        assertEquals(listOf(0x2236, 0x202F, 0x2721, 0x2B32), cmds(mock))
        assertThrows(IllegalArgumentException::class.java) { s.startPeriodic(3.0) }
        assertThrows(IllegalArgumentException::class.java) { s.startPeriodic(1.0, "x") }
        mock.writes().clear()
        mock.queueRead(frame(0x4000, 0x4000))
        val v = s.fetch()!!
        assertEquals(listOf(0xE000), cmds(mock))
        assertEquals(-45.0 + 175.0 * 0x4000 / 65535.0, v[0], 1e-9)
        mock.failNextReads(1)
        assertNull(s.fetch())
        mock.writes().clear()
        s.stopPeriodic()
        assertEquals(listOf(0x3093), cmds(mock))
    }

    @Test
    fun heaterStatusReset() {
        make()
        mock.writes().clear()
        s.setHeater(true)
        s.setHeater(false)
        s.clearStatus()
        assertEquals(listOf(0x306D, 0x3066, 0x3041), cmds(mock))
        val st = byteArrayOf(0x80.toByte(), 0x10, 0)
        st[2] = SHT31Minimal.crc8(st, 0, 2).toByte()
        mock.queueRead(st)
        assertEquals(0x8010, s.readStatus())
        mock.queueRead(byteArrayOf(0x80.toByte(), 0x10, 0x00))
        assertThrows(IOException::class.java) { s.readStatus() }
        mock.writes().clear()
        s.softReset()
        assertEquals(listOf(0x30A2), cmds(mock))
    }

    @Test
    fun dewPoint() {
        make()
        mock.queueRead(frame(Math.round((25.0 + 45.0) * 65535.0 / 175.0).toInt(), Math.round(50.0 * 65535.0 / 100.0).toInt()))
        assertEquals(13.86, s.dewPoint(), 0.05)
    }
}
