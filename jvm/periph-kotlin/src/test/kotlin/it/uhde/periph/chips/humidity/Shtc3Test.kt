package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class Shtc3Test {

    private fun word(code: Int): ByteArray {
        val f = ByteArray(3)
        f[0] = (code shr 8).toByte(); f[1] = code.toByte()
        f[2] = Shtc3Minimal.crc8(f, 0, 2).toByte()
        return f
    }

    private fun trh(t: Int, rh: Int): ByteArray = word(t) + word(rh)

    private fun cmds(c: MockConnection) = c.writes().map { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }

    private lateinit var mock: MockConnection
    private lateinit var s: Shtc3Full

    private fun make() {
        mock = MockConnection()
        s = Shtc3Full(mock)
    }

    @Test
    fun crcReference() {
        assertEquals(0x92, Shtc3Minimal.crc8(byteArrayOf(0xBE.toByte(), 0xEF.toByte()), 0, 2))
        assertEquals(0xAC, Shtc3Minimal.crc8(byteArrayOf(0x00), 0, 1))
    }

    @Test
    fun constructorDoesNoBusAccessAndInitSequence() {
        make()
        assertTrue(mock.writes().isEmpty())
        mock.queueRead(word(0x0807))
        s.init()
        assertEquals(listOf(0x3517, 0x805D, 0xEFC8, 0xB098), cmds(mock))
        assertNotNull(Shtc3Minimal(MockConnection()))
    }

    @Test
    fun initRejectsWrongIdAndBadCrc() {
        make()
        mock.queueRead(word(0x0000))
        assertThrows(IOException::class.java) { s.init() }
        make()
        val bad = word(0x0807)
        bad[2] = (bad[2].toInt() xor 0x55).toByte()
        mock.queueRead(bad)
        assertThrows(IOException::class.java) { s.init() }
    }

    @Test
    fun readConvertsAndSendsCommands() {
        make()
        mock.queueRead(trh(0x6666, 0x6000))
        val m = s.read()
        assertEquals(listOf(0x7866, 0xB098), cmds(mock))
        assertEquals(-45.0 + 175.0 * 0x6666 / 65536.0, m[0], 1e-6)
        assertEquals(37.5, m[1], 1e-6)
    }

    @Test
    fun secondReadWakesFirst() {
        make()
        mock.queueRead(trh(0x6666, 0x6000))
        mock.queueRead(trh(0x6666, 0x6000))
        s.read()
        s.read()
        assertEquals(listOf(0x7866, 0xB098, 0x3517, 0x7866, 0xB098), cmds(mock))
    }

    @Test
    fun fullScaleAndClamp() {
        make()
        mock.queueRead(trh(0xFFFF, 0xFFFF))
        var m = s.read()
        assertEquals(130.0, m[0], 0.01)
        assertTrue(m[1] <= 100.0 && m[1] > 99.9)
        mock.queueRead(trh(0, 0))
        m = s.read()
        assertEquals(-45.0, m[0], 1e-6)
        assertEquals(0.0, m[1], 1e-6)
    }

    @Test
    fun crcMismatchRetriedOnceThenRejected() {
        make()
        val bad = trh(0x6666, 0x6000)
        bad[2] = (bad[2].toInt() xor 0xFF).toByte()
        mock.queueRead(bad)
        mock.queueRead(bad)
        assertThrows(IOException::class.java) { s.read() }
        assertEquals(listOf(0x7866, 0x7866, 0xB098), cmds(mock))
    }

    @Test
    fun crcGlitchRecovered() {
        make()
        val bad = trh(0x6666, 0x6000)
        bad[5] = (bad[5].toInt() xor 0xFF).toByte()
        mock.queueRead(bad)
        mock.queueRead(trh(0x6666, 0x6000))
        assertEquals(37.5, s.read()[1], 1e-6)
        assertEquals(listOf(0x7866, 0x7866, 0xB098), cmds(mock))
    }

    @Test
    fun singleValuesAndRaw() {
        make()
        mock.queueRead(trh(0x4000, 0x4000))
        mock.queueRead(trh(0x4000, 0x4000))
        mock.queueRead(trh(0x1234, 0x5678))
        assertEquals(25.0, s.readHumidity(), 1e-6)
        assertEquals(-45.0 + 175.0 * 0.25, s.readTemperature(), 1e-6)
        assertArrayEquals(intArrayOf(0x1234, 0x5678), s.readRaw())
    }

    @Test
    fun modeCommands() {
        make()
        repeat(3) { mock.queueRead(trh(0x6666, 0x6000)) }
        s.setLowPower(true)
        s.read()
        s.setClockStretching(true)
        s.read()
        s.setLowPower(false)
        s.read()
        assertEquals(listOf(0x609C, 0xB098, 0x3517, 0x6458, 0xB098, 0x3517, 0x7CA2, 0xB098), cmds(mock))
    }

    @Test
    fun sleepControl() {
        make()
        mock.queueRead(trh(0x6666, 0x6000))
        mock.queueRead(trh(0x6666, 0x6000))
        s.setSleepBetween(false)
        s.read()
        s.read()
        s.sleep()
        s.wake()
        assertEquals(listOf(0x7866, 0x7866, 0xB098, 0x3517), cmds(mock))
    }

    @Test
    fun softResetAndReadId() {
        make()
        s.softReset()
        assertEquals(listOf(0x805D), cmds(mock))
        make()
        mock.queueRead(word(0xAB07))
        assertEquals(0xAB07, s.readId())
        assertEquals(listOf(0xEFC8), cmds(mock))
        s.sleep()
        mock.queueRead(word(0x0807))
        assertEquals(0x0807, s.readId() and 0x0FFF)
        assertEquals(listOf(0xEFC8, 0xB098, 0x3517, 0xEFC8, 0xB098), cmds(mock))
        val bad = word(0x0807)
        bad[2] = (bad[2].toInt() xor 0x55).toByte()
        mock.queueRead(bad)
        assertThrows(IOException::class.java) { s.readId() }
    }

    @Test
    fun dewPoint() {
        make()
        val tRaw = Math.round((25.0 + 45.0) * 65536.0 / 175.0).toInt()
        mock.queueRead(trh(tRaw, 0x8000))
        assertEquals(13.86, s.dewPoint(), 0.15)
        mock.queueRead(trh(tRaw, 0))
        assertTrue(s.dewPoint() < -20.0)
    }
}
