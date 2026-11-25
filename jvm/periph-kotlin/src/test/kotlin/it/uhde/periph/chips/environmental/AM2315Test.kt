package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class AM2315Test {

    private fun resp(vararg body: Int): ByteArray {
        val f = ByteArray(body.size + 2)
        for (i in body.indices) f[i] = body[i].toByte()
        val crc = AM2315Minimal.crc16(f, 0, body.size)
        f[body.size] = crc.toByte()
        f[body.size + 1] = (crc shr 8).toByte()
        return f
    }

    private fun readResp(vararg data: Int): ByteArray = resp(0x03, data.size, *data)

    private lateinit var mock: MockConnection
    private lateinit var s: AM2315Full

    private fun make() {
        mock = MockConnection()
        s = AM2315Full(mock)
        s.setMinIntervalMs(0)       // tests do not wait the 2 s between transactions
    }

    private fun lastWrite(): ByteArray = mock.writes().last()

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun crcReference() {
        assertEquals(0xA531, AM2315Minimal.crc16(bytes(0x03, 0x04, 0x01, 0xF4, 0x00, 0xFA), 0, 6))
        assertEquals(0xFEE1, AM2315Minimal.crc16(bytes(0x03, 0x04, 0x03, 0x39, 0x01, 0x15), 0, 6))
        assertEquals(0x92C0, AM2315Minimal.crc16(bytes(0x10, 0x10, 0x02, 0x01, 0x02), 0, 5))
    }

    @Test
    fun constructorDoesNoBusAccess() {
        make()
        assertTrue(mock.writes().isEmpty())
        assertNotNull(AM2315Minimal(MockConnection()))
    }

    @Test
    fun readWakesThenCommandsThenDecodes() {
        make()
        mock.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA))
        val v = s.read()
        assertEquals(2, mock.writes().size)
        assertArrayEquals(bytes(0x00), mock.writes()[0])
        assertArrayEquals(bytes(0x03, 0x00, 0x04), mock.writes()[1])
        assertEquals(25.0, v[0], 1e-9)
        assertEquals(50.0, v[1], 1e-9)
    }

    @Test
    fun datasheetFrame() {
        make()
        mock.queueRead(bytes(0x03, 0x04, 0x03, 0x39, 0x01, 0x15, 0xE1, 0xFE))
        val v = s.read()
        assertEquals(27.7, v[0], 1e-9)
        assertEquals(82.5, v[1], 1e-9)
    }

    @Test
    fun negativeTemperatureIsSignMagnitude() {
        make()
        mock.queueRead(readResp(0x01, 0xF4, 0x80, 0x65))
        assertEquals(-10.1, s.read()[0], 1e-9)
    }

    @Test
    fun wakeNackIsIgnored() {
        make()
        mock.failNextWrites(1, 0)
        mock.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA))
        assertEquals(50.0, s.read()[1], 1e-9)
    }

    @Test
    fun crcRetryRecoversThenFails() {
        make()
        val bad = readResp(0x01, 0xF4, 0x00, 0xFA)
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0xFF).toByte()
        mock.queueRead(bad)
        mock.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA))
        assertEquals(50.0, s.read()[1], 1e-9)
        assertEquals(4, mock.writes().size)
        make()
        mock.queueRead(bad)
        mock.queueRead(bad)
        assertThrows(IOException::class.java) { s.read() }
    }

    @Test
    fun busErrorIsRetriedOnce() {
        make()
        mock.failNextReads(1)
        mock.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA))
        assertEquals(25.0, s.read()[0], 1e-9)
        make()
        mock.failNextReads(2)
        assertThrows(IOException::class.java) { s.read() }
    }

    @Test
    fun errorResponseIsNotRetried() {
        make()
        mock.queueRead(bytes(0x83, 0, 0, 0))
        val e = assertThrows(AM2315Minimal.ErrorResponse::class.java) { s.read() }
        assertEquals(0x83, e.code)
        assertEquals(2, mock.writes().size)
    }

    @Test
    fun minimumIntervalIsEnforced() {
        val c = MockConnection()
        val m = AM2315Minimal(c)
        m.setMinIntervalMs(50)
        c.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA))
        val t0 = System.nanoTime()
        m.read()
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 40)
    }

    @Test
    fun singleValuesAndRaw() {
        make()
        mock.queueRead(readResp(0x00, 0x7B))
        assertEquals(12.3, s.readHumidity(), 1e-9)
        assertArrayEquals(bytes(0x03, 0x00, 0x02), lastWrite())
        mock.queueRead(readResp(0x80, 0x65))
        assertEquals(-10.1, s.readTemperature(), 1e-9)
        assertArrayEquals(bytes(0x03, 0x02, 0x02), lastWrite())
        mock.queueRead(readResp(0x01, 0xF4, 0x80, 0x65))
        assertArrayEquals(intArrayOf(0x01F4, 0x8065), s.readRaw())
    }

    @Test
    fun readFreshReturnsSecond() {
        make()
        mock.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA))
        mock.queueRead(readResp(0x01, 0xF9, 0x01, 0x00))
        val v = s.readFresh()
        assertEquals(25.6, v[0], 1e-9)
        assertEquals(50.5, v[1], 1e-9)
    }

    @Test
    fun registerReadAndLimits() {
        make()
        mock.queueRead(readResp(1, 2, 3))
        assertArrayEquals(bytes(1, 2, 3), s.readRegisters(0x08, 3))
        assertThrows(IllegalArgumentException::class.java) { s.readRegisters(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { s.readRegisters(0, 11) }
    }

    @Test
    fun deviceInfo() {
        make()
        mock.queueRead(readResp(0x09, 0x0F, 0x10, 0x12, 0x34, 0x56, 0x78))
        assertArrayEquals(longArrayOf(0x090F, 0x10, 0x12345678L), s.readDeviceInfo())
        assertArrayEquals(bytes(0x03, 0x08, 0x07), lastWrite())
    }

    @Test
    fun userRegisters() {
        make()
        mock.queueRead(readResp(0x12, 0x34, 0xAB, 0xCD))
        assertArrayEquals(intArrayOf(0x1234, 0xABCD), s.readUserRegisters())
        mock.queueRead(resp(0x10, 0x10, 0x04))
        s.writeUserRegisters(0x1234, 0xABCD)
        assertArrayEquals(resp(0x10, 0x10, 0x04, 0x12, 0x34, 0xAB, 0xCD), lastWrite())
    }

    @Test
    fun writeRegistersFrameAndLimits() {
        make()
        mock.queueRead(resp(0x10, 0x10, 0x02))
        s.writeRegisters(0x10, bytes(1, 2))
        assertArrayEquals(bytes(0x10, 0x10, 0x02, 1, 2, 0xC0, 0x92), lastWrite())
        assertThrows(IllegalArgumentException::class.java) { s.writeRegisters(0x0F, bytes(1)) }
        assertThrows(IllegalArgumentException::class.java) { s.writeRegisters(0x13, bytes(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { s.writeRegisters(0x10, ByteArray(0)) }
        mock.queueRead(bytes(0x82, 0, 0, 0, 0))
        assertThrows(AM2315Minimal.ErrorResponse::class.java) { s.writeRegisters(0x10, bytes(1)) }
    }

    @Test
    fun wake() {
        make()
        s.wake()
        assertEquals(1, mock.writes().size)
        assertArrayEquals(bytes(0x00), mock.writes()[0])
    }
}
