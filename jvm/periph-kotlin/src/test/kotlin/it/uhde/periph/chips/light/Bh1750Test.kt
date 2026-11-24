package it.uhde.periph.chips.light

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class Bh1750Test {

    private fun word(raw: Int): ByteArray = byteArrayOf((raw shr 8).toByte(), raw.toByte())

    private fun cmds(c: MockConnection) = c.writes().map { it[0].toInt() and 0xFF }

    private lateinit var mock: MockConnection
    private lateinit var s: Bh1750Full

    private fun make() {
        mock = MockConnection()
        s = Bh1750Full(mock)
    }

    @Test
    fun constructorDoesNoBusAccessAndInitPowersOn() {
        make()
        assertTrue(mock.writes().isEmpty())
        s.init()
        assertEquals(listOf(0x01), cmds(mock))
        assertNotNull(Bh1750Minimal(MockConnection()))
    }

    @Test
    fun minimalReadConvertsDatasheetExample() {
        val c = MockConnection()
        val m = Bh1750Minimal(c)
        c.queueRead(word(0x8342))
        assertEquals(0x8342 / 1.2, m.read(), 0.01)
        assertEquals(listOf(0x20), cmds(c))
    }

    @Test
    fun measureOnceModes() {
        make()
        mock.queueRead(word(0x12))
        assertEquals(15.0, s.measureOnce(Bh1750Minimal.MODE_H), 1e-3)
        assertEquals(listOf(0x20), cmds(mock))
        mock.writes().clear()
        mock.queueRead(word(0x12))
        assertEquals(7.5, s.measureOnce(Bh1750Minimal.MODE_H2), 1e-3)
        assertEquals(listOf(0x21), cmds(mock))
        mock.writes().clear()
        mock.queueRead(word(0x12))
        assertEquals(15.0, s.measureOnce(Bh1750Minimal.MODE_L), 1e-3)
        assertEquals(listOf(0x23), cmds(mock))
        assertThrows(IllegalArgumentException::class.java) { s.measureOnce(7) }
    }

    @Test
    fun readFollowsCurrentMode() {
        make()
        mock.queueRead(word(0x12))
        s.measureOnce(Bh1750Minimal.MODE_H2)
        mock.writes().clear()
        mock.queueRead(word(0x12))
        assertEquals(7.5, s.read(), 1e-3)
        assertEquals(listOf(0x21), cmds(mock))
        mock.writes().clear()
        mock.queueRead(word(0x12))
        assertEquals(0x12, s.readRaw())
        assertEquals(listOf(0x21), cmds(mock))
    }

    @Test
    fun continuousMode() {
        make()
        s.startContinuous(Bh1750Minimal.MODE_H)
        s.startContinuous(Bh1750Minimal.MODE_H2)
        s.startContinuous(Bh1750Minimal.MODE_L)
        assertEquals(listOf(0x10, 0x11, 0x13), cmds(mock))
        s.startContinuous(Bh1750Minimal.MODE_H2)
        mock.writes().clear()
        mock.queueRead(word(0x12))
        assertEquals(7.5, s.readContinuous(), 1e-3)
        assertTrue(mock.writes().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { s.startContinuous(9) }
    }

    @Test
    fun powerAndReset() {
        make()
        s.powerDown()
        s.powerOn()
        assertEquals(listOf(0x00, 0x01), cmds(mock))
        mock.writes().clear()
        s.reset()
        assertEquals(listOf(0x01, 0x07), cmds(mock))
    }

    @Test
    fun measurementTimeRegister() {
        make()
        assertEquals(69, s.getMeasurementTimeRegister())
        s.setMeasurementTime(69)
        assertEquals(listOf(0x42, 0x65), cmds(mock))
        mock.writes().clear()
        s.setMeasurementTime(254)
        assertEquals(listOf(0x47, 0x7E), cmds(mock))
        assertEquals(254, s.getMeasurementTimeRegister())
        mock.writes().clear()
        s.setMeasurementTime(31)
        assertEquals(listOf(0x40, 0x7F), cmds(mock))
        mock.writes().clear()
        assertThrows(IllegalArgumentException::class.java) { s.setMeasurementTime(30) }
        assertThrows(IllegalArgumentException::class.java) { s.setMeasurementTime(255) }
        assertTrue(mock.writes().isEmpty())
        assertEquals(31, s.getMeasurementTimeRegister())
    }

    @Test
    fun sensitivityScalesEverything() {
        make()
        assertEquals(1 / 1.2, s.luxPerCount(), 1e-9)
        assertEquals(0.5 / 1.2, s.luxPerCount(Bh1750Minimal.MODE_H2), 1e-9)
        assertEquals(0.180, s.measurementTime(), 1e-9)
        assertEquals(0.024, s.measurementTime(Bh1750Minimal.MODE_L), 1e-9)
        s.setSensitivity(2.0)
        assertEquals(138, s.getMeasurementTimeRegister())
        assertEquals(1 / 1.2 / 2, s.luxPerCount(), 1e-9)
        assertEquals(0.360, s.measurementTime(), 1e-9)
        mock.queueRead(word(0x12))
        assertEquals(7.5, s.measureOnce(Bh1750Minimal.MODE_H), 1e-3)
        assertThrows(IllegalArgumentException::class.java) { s.setSensitivity(0.4) }
        assertThrows(IllegalArgumentException::class.java) { s.setSensitivity(3.7) }
        s.setSensitivity(0.45)
        assertEquals(31, s.getMeasurementTimeRegister())
        s.setSensitivity(3.68)
        assertEquals(254, s.getMeasurementTimeRegister())
    }

    @Test
    fun busErrorPropagates() {
        make()
        mock.failNextWrites(1, 0)
        assertThrows(IOException::class.java) { s.measureOnce(Bh1750Minimal.MODE_H) }
    }
}
