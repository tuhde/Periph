package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class HDC1080Test {

    private lateinit var mock: MockConnection
    private lateinit var s: HDC1080Full

    private fun make() {
        mock = MockConnection()
        s = HDC1080Full(mock)
    }

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun assertLast(vararg expected: Int) = assertArrayEquals(b(*expected), mock.writes().last())

    @Test
    fun constructorDoesNoBusAccessAndInitWritesDefault() {
        make()
        assertTrue(mock.writes().isEmpty())
        s.init()
        assertLast(0x02, 0x10, 0x00)
        assertNotNull(HDC1080Minimal(MockConnection()))
    }

    @Test
    fun readSequence() {
        make()
        mock.queueRead(b(0x66, 0x66, 0x80, 0x00))
        val v = s.read()
        assertEquals(1, mock.writes().size)
        assertLast(0x00)
        assertEquals(0x6666 / 65536.0 * 165.0 - 40.0, v[0], 1e-3)
        assertEquals(50.0, v[1], 1e-3)
    }

    @Test
    fun readPollsWhileNackedAndTimesOut() {
        make()
        mock.failNextReads(3)
        mock.queueRead(b(0x66, 0x66, 0x80, 0x00))
        assertEquals(50.0, s.read()[1], 1e-3)
        make()
        mock.failNextReads(1000)
        assertThrows(IOException::class.java) { s.read() }
    }

    @Test
    fun singleMeasurementsSwitchMode() {
        make()
        mock.queueRead(b(0x80, 0x00))
        assertEquals(42.5, s.readTemperature(), 1e-3)
        assertEquals(2, mock.writes().size)
        assertArrayEquals(b(0x02, 0x00, 0x00), mock.writes()[0])
        mock.queueRead(b(0x80, 0x00))
        assertEquals(50.0, s.readHumidity(), 1e-3)
        assertLast(0x01)
    }

    @Test
    fun readRaw() {
        make()
        mock.queueRead(b(0x12, 0x34, 0x56, 0x78))
        assertArrayEquals(intArrayOf(0x1234, 0x5678), s.readRaw())
    }

    @Test
    fun resolutionAndConversionTime() {
        make()
        s.setResolution(11, 8)
        assertLast(0x02, 0x16, 0x00)
        assertEquals(0.00365 + 0.0025, s.conversionTime(), 1e-9)
        s.setResolution(14, 14)
        assertLast(0x02, 0x10, 0x00)
        assertEquals(0.01285, s.conversionTime(), 1e-9)
        assertThrows(IllegalArgumentException::class.java) { s.setResolution(8, 14) }
        assertThrows(IllegalArgumentException::class.java) { s.setResolution(14, 12) }
    }

    @Test
    fun heaterBatteryConfig() {
        make()
        s.setHeater(true)
        assertLast(0x02, 0x30, 0x00)
        s.setHeater(false)
        assertLast(0x02, 0x10, 0x00)
        mock.queueRead(b(0x30, 0x00))
        assertTrue(s.isHeaterOn())
        mock.queueRead(b(0x18, 0x00))
        assertTrue(s.batteryLow())
        mock.queueRead(b(0x10, 0x00))
        assertFalse(s.batteryLow())
        mock.queueRead(b(0x10, 0x00))
        assertEquals(0x1000, s.readConfig())
    }

    @Test
    fun softwareResetRewritesConfig() {
        make()
        s.setHeater(true)
        s.softwareReset()
        val w = mock.writes()
        assertArrayEquals(b(0x02, 0x80, 0x00), w[w.size - 2])
        assertArrayEquals(b(0x02, 0x30, 0x00), w[w.size - 1])
    }

    @Test
    fun serialId() {
        make()
        mock.queueRead(b(0x00, 0x12))
        mock.queueRead(b(0x34, 0x56))
        mock.queueRead(b(0x80, 0x00))
        assertEquals((0x12L shl 24) or (0x3456L shl 8) or 0x100, s.serialId())
    }

    @Test
    fun ids() {
        make()
        mock.queueRead(b(0x54, 0x49))
        assertEquals(0x5449, s.manufacturerId())
        mock.queueRead(b(0x10, 0x50))
        assertEquals(0x1050, s.deviceId())
    }
}
