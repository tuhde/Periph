package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class HS30xxTest {

    private fun frame(rh: Int, t: Int, status: Int): ByteArray =
        byteArrayOf(((status shl 6) or (rh shr 8)).toByte(), rh.toByte(), (t shr 6).toByte(), ((t and 0x3F) shl 2).toByte())

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun assertWrites(c: MockConnection, vararg expected: ByteArray) {
        val w = c.writes()
        assertEquals(expected.size, w.size)
        for (i in expected.indices) assertArrayEquals(expected[i], w[i], "write $i")
    }

    private lateinit var mock: MockConnection
    private lateinit var s: HS30xxFull

    private fun make() {
        mock = MockConnection()
        s = HS30xxFull(mock)
    }

    @Test
    fun constructorDoesNoBusAccess() {
        make()
        assertTrue(mock.writes().isEmpty())
    }

    @Test
    fun aliasesAreTheSameTypes() {
        val m = MockConnection()
        val a: HS30xxFull = HS3001Full(m)
        val c: HS30xxMinimal = HS3003Minimal(m)
        assertNotNull(a)
        assertNotNull(c)
        assertNotNull(HS3002Full(m))
        assertNotNull(HS3001Minimal(m))
    }

    @Test
    fun conversions() {
        make()
        mock.queueRead(frame(0x2000, 0x1000, 0))
        var m = s.read()
        assertWrites(mock, ByteArray(0))
        assertEquals(50.0, m[1], 0.01)
        assertEquals(1.25, m[0], 0.01)
        assertEquals(8192 / 16383.0 * 100.0, m[1], 1e-9)
        mock.queueRead(frame(0x3FFF, 0x3FFF, 0))
        m = s.read()
        assertEquals(100.0, m[1], 1e-6)
        assertEquals(125.0, m[0], 1e-6)
        mock.queueRead(frame(0, 0, 0))
        m = s.read()
        assertEquals(0.0, m[1], 1e-9)
        assertEquals(-40.0, m[0], 1e-9)
    }

    @Test
    fun masksUndeterminedBits() {
        make()
        mock.queueRead(b(0x20, 0x00, 0x10, 0x03))
        assertEquals(0x400 / 16383.0 * 165.0 - 40.0, s.read()[0], 1e-9)
    }

    @Test
    fun staleRetriesOnceThenFails() {
        make()
        mock.queueRead(frame(0x2000, 0x1000, 1))
        mock.queueRead(frame(0x2000, 0x1000, 0))
        assertEquals(50.0, s.read()[1], 0.01)
        assertEquals(1, mock.writes().size)
        mock.queueRead(frame(0, 0, 1))
        mock.queueRead(frame(0, 0, 1))
        assertThrows(IOException::class.java) { s.read() }
    }

    @Test
    fun busErrorOnRequest() {
        make()
        mock.failNextWrites(1, 0)
        assertThrows(IOException::class.java) { s.read() }
    }

    @Test
    fun singleValuesAndRaw() {
        make()
        mock.queueRead(frame(0x2000, 0x1000, 0))
        assertEquals(1.25, s.readTemperature(), 0.01)
        mock.queueRead(b(0x20, 0x00))
        assertEquals(50.0, s.readHumidity(), 0.01)
        mock.queueRead(frame(0x1234, 0x0ABC, 0))
        assertArrayEquals(intArrayOf(0x1234, 0x0ABC, 0), s.readRaw())
        mock.queueRead(frame(0x1234, 0x0ABC, 1))
        assertEquals(1, s.readRaw()[2])
        assertThrows(IllegalArgumentException::class.java) { s.setMeasurementWait(0) }
        s.setMeasurementWait(5)
        mock.queueRead(frame(0x2000, 0x1000, 0))
        assertEquals(50.0, s.readHumidity(), 0.01)
    }

    @Test
    fun programmingCommandsAndNvmRead() {
        make()
        s.enterProgrammingMode()
        s.exitProgrammingMode()
        assertWrites(mock, b(0xA0, 0, 0), b(0x80, 0, 0))
        make()
        mock.queueRead(b(0x81, 0x12, 0x34))
        assertEquals(0x1234, s.readNvm(0x1E))
        assertWrites(mock, b(0x1E, 0, 0))
        mock.queueRead(b(0x80, 0x12, 0x34))
        assertThrows(IOException::class.java) { s.readNvm(0x1E) }
    }

    @Test
    fun nvmMethodsNeedPowerCycle() {
        make()
        assertThrows(IllegalStateException::class.java) { s.getResolution(null) }
        assertThrows(IllegalStateException::class.java) { s.setResolution(12, 12, null) }
        assertThrows(IllegalStateException::class.java) { s.readId(null) }
        assertTrue(mock.writes().isEmpty())
    }

    @Test
    fun getResolution() {
        make()
        mock.queueRead(b(0x81, 0x0C, 0x00))
        mock.queueRead(b(0x81, 0x08, 0x05))
        var cycles = 0
        assertArrayEquals(intArrayOf(14, 12), s.getResolution { cycles++ })
        assertEquals(1, cycles)
        assertWrites(mock, b(0xA0, 0, 0), b(0x06, 0, 0), b(0x11, 0, 0), b(0x80, 0, 0))
    }

    @Test
    fun setResolutionPreservesOtherBits() {
        make()
        mock.queueRead(b(0x81, 0x0C, 0x07))
        mock.queueRead(b(0x81, 0x0C, 0x21))
        s.setResolution(8, 12) {}
        assertWrites(mock, b(0xA0, 0, 0), b(0x06, 0, 0), b(0x46, 0x00, 0x07),
            b(0x11, 0, 0), b(0x51, 0x08, 0x21), b(0x80, 0, 0))
    }

    @Test
    fun setResolutionOneChannelAndInvalid() {
        make()
        mock.queueRead(b(0x81, 0x00, 0x00))
        s.setResolution(null, 10) {}
        assertWrites(mock, b(0xA0, 0, 0), b(0x11, 0, 0), b(0x51, 0x04, 0x00), b(0x80, 0, 0))
        make()
        assertThrows(IllegalArgumentException::class.java) { s.setResolution(9, null) {} }
        assertTrue(mock.writes().isEmpty())
    }

    @Test
    fun failedNvmReadStillLeavesProgrammingMode() {
        make()
        mock.queueRead(b(0x00, 0, 0))
        assertThrows(IOException::class.java) { s.getResolution {} }
        assertArrayEquals(b(0x80, 0, 0), mock.writes().last())
    }

    @Test
    fun readId() {
        make()
        mock.queueRead(b(0x81, 0xDE, 0xAD))
        mock.queueRead(b(0x81, 0xBE, 0xEF))
        assertEquals(0xDEADBEEFL, s.readId {})
    }

    @Test
    fun dewPoint() {
        make()
        mock.queueRead(frame(8191, 6453, 0))
        assertEquals(13.86, s.dewPoint(), 0.1)
        mock.queueRead(frame(0, 0x1000, 0))
        assertEquals(-273.15, s.dewPoint(), 1e-9)
    }
}
