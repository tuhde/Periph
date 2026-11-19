package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class HS40xxTest {

    private fun crc8(vararg d: Int): Int = HS40xxMinimal.crc8(ByteArray(d.size) { d[it].toByte() })

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun ht(h: Int, t: Int): ByteArray {
        val d = intArrayOf(h shr 8, h and 0xFF, t shr 8, t and 0xFF)
        return b(d[0], d[1], d[2], d[3], crc8(*d))
    }

    private fun tOnly(t: Int): ByteArray = b(t shr 8, t and 0xFF, crc8(0, 0, t shr 8, t and 0xFF))

    private fun withStatus(status: Int, frame: ByteArray): ByteArray = byteArrayOf(status.toByte()) + frame

    private fun assertWrites(c: MockConnection, vararg expected: ByteArray) {
        val w = c.writes()
        assertEquals(expected.size, w.size)
        for (i in expected.indices) assertArrayEquals(expected[i], w[i], "write $i")
    }

    private lateinit var mock: MockConnection
    private lateinit var s: HS40xxFull

    private fun make() {
        mock = MockConnection()
        s = HS40xxFull(mock)
    }

    @Test
    fun crcDatasheetExample() {
        assertEquals(0xF2, crc8(0x05, 0x80, 0x05, 0x80))
    }

    @Test
    fun constructorDoesNoBusAccess() {
        make()
        assertTrue(mock.writes().isEmpty())
    }

    @Test
    fun aliasesAreTheSameTypes() {
        val m = MockConnection()
        val a: HS40xxFull = HS4001Full(m)
        val c: HS40xxMinimal = HS4003Minimal(m)
        assertNotNull(a)
        assertNotNull(c)
        assertNotNull(HS4003Full(m))
        assertNotNull(HS4001Minimal(m))
    }

    @Test
    fun datasheetExampleFrame() {
        make()
        mock.queueRead(b(0x05, 0x80, 0x05, 0x80, 0xF2))
        val m = s.read()
        assertWrites(mock, b(0xF5))
        assertEquals(8.59, m[1], 0.01)
        assertEquals(-25.8, m[0], 0.05)
    }

    @Test
    fun fullScaleAndZero() {
        make()
        mock.queueRead(ht(0x3FFF, 0x3FFF))
        var m = s.read()
        assertEquals(100.0, m[1], 1e-6)
        assertEquals(125.0, m[0], 1e-6)
        mock.queueRead(ht(0, 0))
        m = s.read()
        assertEquals(0.0, m[1], 1e-9)
        assertEquals(-40.0, m[0], 1e-9)
    }

    @Test
    fun nackIsRetriedAndPersistentNackFails() {
        make()
        mock.failNextReads(2)
        mock.queueRead(ht(0x2000, 0x1000))
        assertEquals(50.0, s.read()[1], 0.01)
        mock.failNextReads(1000)
        assertThrows(IOException::class.java) { s.read() }
    }

    @Test
    fun crcMismatchRetriedOnce() {
        make()
        val bad = ht(0x2000, 0x1000)
        bad[4] = (bad[4].toInt() xor 0xFF).toByte()
        mock.queueRead(bad)
        mock.queueRead(ht(0x2000, 0x1000))
        assertEquals(50.0, s.read()[1], 0.01)
        assertWrites(mock, b(0xF5), b(0xF5))
        make()
        mock.queueRead(bad)
        mock.queueRead(bad)
        assertThrows(IOException::class.java) { s.read() }
    }

    @Test
    fun singleValuesHoldAndRaw() {
        make()
        mock.queueRead(tOnly(0x1000))
        assertEquals(4096 / 16383.0 * 165.0 - 40.0, s.readTemperature(), 1e-9)
        mock.queueRead(ht(0x2000, 0x1000))
        assertEquals(50.0, s.readHumidity(), 0.01)
        mock.queueRead(ht(0x2000, 0x1000))
        assertEquals(50.0, s.readHold()[1], 0.01)
        mock.queueRead(ht(0x1234, 0x0ABC))
        assertArrayEquals(intArrayOf(0x1234, 0x0ABC), s.readRaw())
        assertWrites(mock, b(0xF3), b(0xF5), b(0xE5), b(0xF5))
    }

    @Test
    fun registers() {
        make()
        mock.queueRead(b(0x5A))
        assertEquals(0x5A, s.readRegister(0x02))
        s.writeRegister(0x03, 0x0A)
        assertWrites(mock, b(0xA7, 0x02), b(0xA6, 0x03, 0x0A))
    }

    @Test
    fun resolution() {
        make()
        mock.queueRead(b(0xF3))
        s.setResolution(8, 12)
        assertWrites(mock, b(0xA7, 0x00), b(0xA6, 0x00, 0xF2))
        make()
        assertThrows(IllegalArgumentException::class.java) { s.setResolution(9, null) }
        assertTrue(mock.writes().isEmpty())
        mock.queueRead(b(0x0B))
        assertArrayEquals(intArrayOf(12, 14), s.getResolution())
    }

    @Test
    fun periodicWithoutAlerts() {
        make()
        mock.queueRead(b(0x00))
        mock.queueRead(b(0x01))
        s.startPeriodic(2.5)
        assertWrites(mock, b(0xA7, 0x03), b(0xA7, 0x02), b(0xA6, 0x02, 0xA1))
        assertThrows(IllegalArgumentException::class.java) { s.startPeriodic(3.0) }
        mock.queueRead(ht(0x2000, 0x1000))
        val p = s.readPeriodic()
        assertEquals(0.0, p[2], 0.0)
        assertEquals(50.0, p[1], 0.01)
        s.stopPeriodic()
        assertArrayEquals(b(0x30), mock.writes().last())
    }

    @Test
    fun alertsAndStatus() {
        make()
        assertThrows(IllegalStateException::class.java) { s.pollInterrupt() }
        mock.queueRead(b(0x00))
        s.enableInterrupt(HS40xxFull.SOURCE_RH_HIGH)
        assertWrites(mock, b(0xA7, 0x03), b(0xA6, 0x03, 0x02))
        mock.queueRead(withStatus(0x0A, ht(0x2000, 0x1000)))
        assertEquals(0x0A, s.readPeriodic()[2].toInt())
        mock.queueRead(withStatus(0x02, ht(0x2000, 0x1000)))
        assertEquals(0x02, s.pollInterrupt())
        mock.queueRead(b(0x02))
        s.disableInterrupt(HS40xxFull.SOURCE_RH_HIGH)
        assertArrayEquals(b(0xA6, 0x03, 0x00), mock.writes().last())
    }

    @Test
    fun thresholds() {
        make()
        s.setAlertThresholds(rhHigh = 60.0, rhHighReset = 55.0)
        assertWrites(mock, b(0xA6, 0x0F, 0x66), b(0xA6, 0x10, 0x26), b(0xA6, 0x0D, 0x33), b(0xA6, 0x0E, 0x23))
        make()
        s.setAlertThresholds(tempHigh = 125.0, tempLow = -40.0)
        assertWrites(mock, b(0xA6, 0x07, 0xFF), b(0xA6, 0x08, 0x3F), b(0xA6, 0x09, 0x00), b(0xA6, 0x0A, 0x00))
    }

    @Test
    fun polarityIdAndReset() {
        make()
        mock.queueRead(b(0xB1))
        s.setAlertPolarity(false)
        assertArrayEquals(b(0xA6, 0x02, 0xB0), mock.writes()[1])
        mock.queueRead(b(0xDE, 0xAD, 0xBE, 0xEF))
        assertEquals(0xDEADBEEFL, s.readId())
        make()
        s.softReset()
        assertWrites(mock, b(0x30), b(0xFE))
    }

    @Test
    fun dewPoint() {
        make()
        mock.queueRead(ht((0.5 * 16383).toInt(), ((25.0 + 40.0) / 165.0 * 16383).toInt()))
        assertEquals(13.86, s.dewPoint(), 0.1)
        mock.queueRead(ht(0, 0x1000))
        assertEquals(-273.15, s.dewPoint(), 1e-9)
    }
}
