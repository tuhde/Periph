package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class HTU21DFTest {

    private fun frame(code: Int): ByteArray {
        val f = ByteArray(3)
        f[0] = (code shr 8).toByte(); f[1] = code.toByte()
        f[2] = HTU21DFMinimal.crc8(f, 0, 2).toByte()
        return f
    }

    private fun rhCode(rh: Double) = (Math.round((rh + 6.0) * 65536.0 / 125.0).toInt() and 0xFFFC) or 0x02
    private fun tCode(t: Double) = Math.round((t + 46.85) * 65536.0 / 175.72).toInt() and 0xFFFC

    private fun cmds(c: MockConnection) = c.writes().map { it[0].toInt() and 0xFF }

    private fun lastWrite(c: MockConnection, a: Int, b: Int): Boolean {
        val w = c.writes().last()
        return w.size == 2 && (w[0].toInt() and 0xFF) == a && (w[1].toInt() and 0xFF) == b
    }

    private lateinit var mock: MockConnection
    private lateinit var s: HTU21DFFull

    private fun make() {
        mock = MockConnection()
        s = HTU21DFFull(mock)
    }

    private fun corrupt(f: ByteArray): ByteArray {
        val bad = f.copyOf()
        bad[2] = (bad[2].toInt() xor 0xFF).toByte()
        return bad
    }

    @Test
    fun crcReference() {
        assertEquals(0x7C, HTU21DFMinimal.crc8(byteArrayOf(0x68, 0x3A), 0, 2))
        assertEquals(0x6B, HTU21DFMinimal.crc8(byteArrayOf(0x4E, 0x85.toByte()), 0, 2))
        assertEquals(0x79, HTU21DFMinimal.crc8(byteArrayOf(0xDC.toByte()), 0, 1))
    }

    @Test
    fun constructorDoesNoBusAccessAndInitResets() {
        make()
        assertTrue(mock.writes().isEmpty())
        s.init()
        assertEquals(listOf(0xFE), cmds(mock))
        assertNotNull(HTU21DFMinimal(MockConnection()))
    }

    @Test
    fun readUsesF3ThenF5() {
        make()
        mock.queueRead(frame(tCode(25.0)))
        mock.queueRead(frame(rhCode(50.0)))
        val v = s.read()
        assertEquals(listOf(0xF3, 0xF5), cmds(mock))
        assertEquals(25.0, v[0], 0.05)
        assertEquals(50.0, v[1], 0.05)
    }

    @Test
    fun datasheetConversion() {
        make()
        mock.queueRead(frame(0x683A)); mock.queueRead(frame(0x683A))
        val v = s.read()
        assertEquals(-46.85 + 175.72 * 0x6838 / 65536.0, v[0], 1e-6)
        assertEquals(-6.0 + 125.0 * 0x6838 / 65536.0, v[1], 1e-6)
    }

    @Test
    fun readPollsWhileNacked() {
        make()
        mock.failNextReads(3)
        mock.queueRead(frame(tCode(20.0)))
        mock.queueRead(frame(rhCode(40.0)))
        assertEquals(40.0, s.read()[1], 0.05)
    }

    @Test
    fun timeoutAndCrcThrow() {
        make()
        mock.failNextReads(1000)
        assertThrows(IOException::class.java) { s.read() }
        make()
        val bad = corrupt(frame(0x6000))
        mock.queueRead(bad); mock.queueRead(bad)
        assertThrows(IOException::class.java) { s.read() }
        make()
        mock.queueRead(bad); mock.queueRead(frame(tCode(25.0))); mock.queueRead(frame(rhCode(50.0)))
        assertEquals(25.0, s.read()[0], 0.05)
        assertEquals(listOf(0xF3, 0xF3, 0xF5), cmds(mock))
    }

    @Test
    fun humidityIsNotClamped() {
        make()
        mock.queueRead(frame(0)); mock.queueRead(frame(0))
        assertEquals(-6.0, s.read()[1], 1e-9)
    }

    @Test
    fun singleValuesRawAndCompensated() {
        make()
        mock.queueRead(frame(rhCode(30.0)))
        assertEquals(30.0, s.readHumidity(), 0.05)
        mock.queueRead(frame(tCode(-10.0)))
        assertEquals(-10.0, s.readTemperature(), 0.05)
        assertEquals(listOf(0xF5, 0xF3), cmds(mock))
        mock.queueRead(frame(0x1237)); mock.queueRead(frame(0x4321))
        assertArrayEquals(intArrayOf(0x1234, 0x4320), s.readRaw())
        mock.queueRead(frame(tCode(35.0))); mock.queueRead(frame(rhCode(50.0)))
        assertEquals(51.5, s.readHumidityCompensated(), 0.1)
    }

    @Test
    fun holdMasterCommands() {
        make()
        s.setHoldMaster(true)
        mock.queueRead(frame(tCode(25.0))); mock.queueRead(frame(rhCode(50.0)))
        s.read()
        assertEquals(listOf(0xE3, 0xE5), cmds(mock))
    }

    @Test
    fun resolution() {
        make()
        mock.setRegister(0xE7, 0x02)
        s.setResolution(3)
        assertTrue(lastWrite(mock, 0xE6, 0x83))
        s.setResolution(1)
        assertTrue(lastWrite(mock, 0xE6, 0x03))
        s.setResolution(2)
        assertTrue(lastWrite(mock, 0xE6, 0x82))
        assertThrows(IllegalArgumentException::class.java) { s.setResolution(4) }
        for ((reg, res) in listOf(0x83 to 3, 0x03 to 1, 0x82 to 2, 0x02 to 0)) {
            mock.setRegister(0xE7, reg)
            assertEquals(res, s.getResolution())
        }
    }

    @Test
    fun heaterBatteryAndUserRegister() {
        make()
        mock.setRegister(0xE7, 0x02)
        s.setHeater(true)
        assertTrue(lastWrite(mock, 0xE6, 0x06))
        mock.setRegister(0xE7, 0x06)
        assertTrue(s.heater())
        s.setHeater(false)
        assertTrue(lastWrite(mock, 0xE6, 0x02))
        mock.setRegister(0xE7, 0x02)
        assertFalse(s.heater())
        assertFalse(s.batteryLow())
        mock.setRegister(0xE7, 0x42)
        assertTrue(s.batteryLow())
        assertEquals(0x42, s.readUserRegister())
    }

    @Test
    fun softResetAndDewPoint() {
        make()
        s.softReset()
        assertEquals(listOf(0xFE), cmds(mock))
        mock.queueRead(frame(tCode(25.0))); mock.queueRead(frame(rhCode(50.0)))
        assertEquals(13.86, s.dewPoint(), 0.1)
    }
}
