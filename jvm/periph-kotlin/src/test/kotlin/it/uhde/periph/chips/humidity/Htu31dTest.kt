package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class Htu31dTest {

    private fun word(code: Int): ByteArray {
        val f = ByteArray(3)
        f[0] = (code shr 8).toByte(); f[1] = code.toByte()
        f[2] = Htu31dMinimal.crc8(f, 0, 2).toByte()
        return f
    }

    private fun trh(t: Int, rh: Int): ByteArray = word(t) + word(rh)

    private fun cmds(c: MockConnection) = c.writes().map { it[0].toInt() and 0xFF }

    private lateinit var mock: MockConnection
    private lateinit var s: Htu31dFull

    private fun make() {
        mock = MockConnection()
        s = Htu31dFull(mock)
    }

    @Test
    fun crcReference() {
        assertEquals(0x79, Htu31dMinimal.crc8(byteArrayOf(0xDC.toByte()), 0, 1))
        assertEquals(0x7C, Htu31dMinimal.crc8(byteArrayOf(0x68, 0x3A), 0, 2))
        assertEquals(0x6B, Htu31dMinimal.crc8(byteArrayOf(0x4E, 0x85.toByte()), 0, 2))
    }

    @Test
    fun constructorDoesNoBusAccessAndInitResets() {
        make()
        assertTrue(mock.writes().isEmpty())
        s.init()
        assertEquals(listOf(0x1E), cmds(mock))
        assertNotNull(Htu31dMinimal(MockConnection()))
    }

    @Test
    fun readConvertsAndSendsCommands() {
        make()
        mock.queueRead(trh(0x683A, 0x8000))
        val m = s.read()
        assertEquals(listOf(0x5E, 0x00), cmds(mock))
        assertEquals(-40.0 + 165.0 * 0x683A / 65535.0, m[0], 1e-6)
        assertEquals(100.0 * 0x8000 / 65535.0, m[1], 1e-6)
    }

    @Test
    fun fullScaleAndClamp() {
        make()
        mock.queueRead(trh(0xFFFF, 0xFFFF))
        var m = s.read()
        assertEquals(125.0, m[0], 1e-6)
        assertEquals(100.0, m[1], 1e-6)
        mock.queueRead(trh(0, 0))
        m = s.read()
        assertEquals(-40.0, m[0], 1e-6)
        assertEquals(0.0, m[1], 1e-6)
    }

    @Test
    fun crcMismatchThrows() {
        make()
        val bad = trh(0x683A, 0x8000)
        bad[2] = (bad[2].toInt() xor 0xFF).toByte()
        mock.queueRead(bad)
        assertThrows(IOException::class.java) { s.read() }
        val bad2 = trh(0x683A, 0x8000)
        bad2[5] = (bad2[5].toInt() xor 0xFF).toByte()
        mock.queueRead(bad2)
        assertThrows(IOException::class.java) { s.read() }
    }

    @Test
    fun singleValuesAndRaw() {
        make()
        mock.queueRead(word(0x4000))
        assertEquals(100.0 * 0x4000 / 65535.0, s.readHumidity(), 1e-6)
        assertEquals(listOf(0x5E, 0x10), cmds(mock))
        mock.writes().clear()
        mock.queueRead(word(0x4000))
        assertEquals(-40.0 + 165.0 * 0x4000 / 65535.0, s.readTemperature(), 1e-6)
        assertEquals(listOf(0x5E, 0x00), cmds(mock))
        mock.queueRead(trh(0x1234, 0x5678))
        assertArrayEquals(intArrayOf(0x1234, 0x5678), s.readRaw())
    }

    @Test
    fun resolution() {
        make()
        assertArrayEquals(intArrayOf(3, 3), s.getResolution())
        assertEquals(0.02032, s.conversionTime(), 1e-9)
        s.setResolution(2, 1)
        mock.queueRead(trh(0x4000, 0x4000))
        s.read()
        assertEquals(0x40 or (2 shl 3) or (1 shl 1), cmds(mock)[0])
        assertThrows(IllegalArgumentException::class.java) { s.setResolution(4, 0) }
        assertThrows(IllegalArgumentException::class.java) { s.setResolution(0, -1) }
        mock.writes().clear()
        s.setResolution(1, 2)
        s.startConversion()
        assertEquals(listOf(0x40 or (1 shl 3) or (2 shl 1)), cmds(mock))
    }

    @Test
    fun readResultUsesReadCommandOnly() {
        make()
        mock.queueRead(trh(0x2000, 0x6000))
        val m = s.readResult()
        assertEquals(listOf(0x00), cmds(mock))
        assertEquals(100.0 * 0x6000 / 65535.0, m[1], 1e-6)
    }

    @Test
    fun heaterAndDiagnostic() {
        make()
        s.setHeater(true)
        s.setHeater(false)
        assertEquals(listOf(0x04, 0x02), cmds(mock))
        mock.queueRead(byteArrayOf(0x01))
        assertTrue(s.isHeaterOn())
        mock.queueRead(byteArrayOf(0xA5.toByte()))
        assertEquals(0xA5, s.diagnostic())
        mock.queueRead(byteArrayOf(0xA5.toByte()))
        val f = s.diagnosticFlags()
        assertEquals(true, f["nvm_err"])
        assertEquals(true, f["rh_high_err"])
        assertEquals(true, f["t_high_err"])
        assertEquals(true, f["heater_on"])
        assertEquals(false, f["rh_overrun"])
        assertEquals(false, f["t_low_err"])
    }

    @Test
    fun serialNumber() {
        make()
        val d = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0)
        d[3] = Htu31dMinimal.crc8(d, 0, 3).toByte()
        mock.queueRead(d)
        assertEquals(0xABCDEF, s.serialNumber())
        val bad = d.copyOf()
        bad[3] = (bad[3].toInt() xor 0x55).toByte()
        mock.queueRead(bad)
        assertThrows(IOException::class.java) { s.serialNumber() }
    }

    @Test
    fun softResetRestoresDefaults() {
        make()
        s.setResolution(0, 1)
        s.softReset()
        assertEquals(listOf(0x1E), cmds(mock))
        assertArrayEquals(intArrayOf(3, 3), s.getResolution())
    }

    @Test
    fun dewPoint() {
        make()
        mock.queueRead(trh(Math.round(65.0 * 65535.0 / 165.0).toInt(), Math.round(0.5 * 65535.0).toInt()))
        assertEquals(13.86, s.dewPoint(), 0.15)
    }
}
