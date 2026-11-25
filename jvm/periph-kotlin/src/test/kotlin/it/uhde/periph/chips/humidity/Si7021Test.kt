package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class Si7021Test {

    private fun frame(code: Int): ByteArray {
        val f = ByteArray(3)
        f[0] = (code shr 8).toByte(); f[1] = code.toByte()
        f[2] = Si7021Minimal.crc8(f, 0, 2).toByte()
        return f
    }

    private fun rhCode(rh: Double) = Math.round((rh + 6.0) * 65536.0 / 125.0).toInt()
    private fun tCode(t: Double) = Math.round((t + 46.85) * 65536.0 / 175.72).toInt()
    private fun tBytes(t: Double) = byteArrayOf((tCode(t) shr 8).toByte(), tCode(t).toByte())

    private fun cmds(c: MockConnection) = c.writes().map { it[0].toInt() and 0xFF }

    private fun lastWrite(c: MockConnection, a: Int, b: Int): Boolean {
        val w = c.writes().last()
        return w.size == 2 && (w[0].toInt() and 0xFF) == a && (w[1].toInt() and 0xFF) == b
    }

    private lateinit var mock: MockConnection
    private lateinit var s: Si7021Full

    private fun make() {
        mock = MockConnection()
        s = Si7021Full(mock)
    }

    @Test
    fun crcReference() {
        assertEquals(0x00, Si7021Minimal.crc8(byteArrayOf(0, 0), 0, 2))
        assertEquals(0x31, Si7021Minimal.crc8(byteArrayOf(1), 0, 1))
    }

    @Test
    fun initDoesNoBusAccessAndReadUsesF5ThenE0() {
        make()
        assertTrue(mock.writes().isEmpty())
        mock.queueRead(frame(rhCode(50.0)))
        mock.queueRead(tBytes(25.0))
        val v = s.read()
        assertEquals(listOf(0xF5, 0xE0), cmds(mock))
        assertEquals(25.0, v[0], 0.02)
        assertEquals(50.0, v[1], 0.02)
        assertNotNull(Si7021Minimal(MockConnection()))
    }

    @Test
    fun readPollsWhileNacked() {
        make()
        mock.failNextReads(3)
        mock.queueRead(frame(rhCode(40.0)))
        mock.queueRead(tBytes(20.0))
        assertEquals(40.0, s.read()[1], 0.02)
    }

    @Test
    fun timeoutAndCrcThrow() {
        make()
        mock.failNextReads(1000)
        assertThrows(IOException::class.java) { s.read() }
        make()
        val bad = frame(0x6000)
        bad[2] = (bad[2].toInt() xor 0xFF).toByte()
        mock.queueRead(bad)
        assertThrows(IOException::class.java) { s.read() }
    }

    @Test
    fun humidityIsClamped() {
        make()
        mock.queueRead(frame(0x0000)); mock.queueRead(byteArrayOf(0, 0))
        assertEquals(0.0, s.read()[1], 1e-9)
        mock.queueRead(frame(0xFFFC)); mock.queueRead(byteArrayOf(0, 0))
        assertEquals(100.0, s.read()[1], 1e-9)
    }

    @Test
    fun singleValuesAndRaw() {
        make()
        mock.queueRead(frame(rhCode(30.0)))
        assertEquals(30.0, s.readHumidity(), 0.02)
        mock.queueRead(frame(tCode(-10.0)))
        assertEquals(-10.0, s.readTemperature(), 0.02)
        mock.queueRead(tBytes(20.0))
        assertEquals(20.0, s.readTemperaturePrevious(), 0.02)
        assertEquals(listOf(0xF5, 0xF3, 0xE0), cmds(mock))
        mock.queueRead(frame(0x1234))
        assertEquals(0x1234, s.readRawHumidity())
        mock.queueRead(frame(0x4321))
        assertEquals(0x4321, s.readRawTemperature())
    }

    @Test
    fun holdMasterCommands() {
        make()
        s.setClockStretching(true)
        mock.queueRead(frame(rhCode(50.0))); mock.queueRead(tBytes(25.0))
        s.read()
        mock.queueRead(frame(0x6666))
        s.readTemperature()
        assertEquals(listOf(0xE5, 0xE0, 0xE3), cmds(mock))
    }

    @Test
    fun resolution() {
        make()
        mock.setRegister(0xE7, 0x3A)
        s.setResolution(11, 11)
        assertTrue(lastWrite(mock, 0xE6, 0xBB))
        s.setResolution(8, 12)
        assertTrue(lastWrite(mock, 0xE6, 0x3B))
        s.setResolution(10, 13)
        assertTrue(lastWrite(mock, 0xE6, 0xBA))
        assertThrows(IllegalArgumentException::class.java) { s.setResolution(12, 12) }
        mock.setRegister(0xE7, 0xBB)
        assertArrayEquals(intArrayOf(11, 11), s.getResolution())
        mock.setRegister(0xE7, 0x3B)
        assertArrayEquals(intArrayOf(8, 12), s.getResolution())
        mock.setRegister(0xE7, 0xBA)
        assertArrayEquals(intArrayOf(10, 13), s.getResolution())
        mock.setRegister(0xE7, 0x3A)
        assertArrayEquals(intArrayOf(12, 14), s.getResolution())
    }

    @Test
    fun heaterVddAndUserRegister() {
        make()
        mock.setRegister(0xE7, 0x3A)
        s.setHeater(true)
        assertTrue(lastWrite(mock, 0xE6, 0x3E))
        mock.setRegister(0xE7, 0x3E)
        assertTrue(s.isHeaterOn())
        s.setHeater(false)
        assertTrue(lastWrite(mock, 0xE6, 0x3A))
        mock.setRegister(0xE7, 0x3A)
        assertFalse(s.isHeaterOn())
        assertFalse(s.vddLow())
        mock.setRegister(0xE7, 0x7A)
        assertTrue(s.vddLow())
        mock.setRegister(0xE7, 0x3A)
        assertEquals(0x3A, s.readUserRegister())
        s.writeUserRegister(0x00)
        assertTrue(lastWrite(mock, 0xE6, 0x3A))
        assertEquals(0.017, s.conversionTime(), 1e-9)
    }

    @Test
    fun resetAndIdentification() {
        mock = MockConnection()
        mock.setAddressWidth(2)
        mock.setRegister(0xFA0F, 0x11, 0, 0x22, 0, 0x33, 0, 0x44, 0)
        mock.setRegister(0xFCC9, 0x15, 0x66, 0, 0x77, 0x88, 0)
        mock.setRegister(0x84B8, 0xFF)
        s = Si7021Full(mock)
        assertEquals(0x1122334415667788L, s.serialNumber())
        assertEquals(0x15, s.deviceType())
        assertEquals(0xFF, s.firmwareRevision())
        mock.writes().clear()
        s.softReset()
        assertEquals(listOf(0xFE), cmds(mock))
    }

    @Test
    fun dewPoint() {
        make()
        mock.queueRead(frame(rhCode(50.0)))
        mock.queueRead(tBytes(25.0))
        assertEquals(13.86, s.dewPoint(), 0.05)
    }
}
