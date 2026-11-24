package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class Ms5611Test {

    private val coeffs = intArrayOf(40127, 36924, 23317, 23282, 33464, 28312)
    private val d1 = 9085466
    private val d2 = 8569150

    private fun prom(corrupt: Boolean): IntArray {
        val w = intArrayOf(0x3132, coeffs[0], coeffs[1], coeffs[2], coeffs[3], coeffs[4], coeffs[5], 0x5A3 shl 4)
        w[7] = w[7] or Ms5611Minimal.crc4(w)
        if (corrupt) w[3] = w[3] xor 0x0100
        return w
    }

    private fun queueProm(c: MockConnection, corrupt: Boolean = false) {
        for (x in prom(corrupt)) c.queueRead(byteArrayOf((x shr 8).toByte(), x.toByte()))
    }

    private fun queueAdc(c: MockConnection, v: Int) {
        c.queueRead(byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
    }

    private fun cmds(c: MockConnection) = c.writes().map { it[0].toInt() and 0xFF }

    private lateinit var mock: MockConnection
    private lateinit var s: Ms5611Full

    private fun make() {
        mock = MockConnection()
        s = Ms5611Full(mock)
    }

    private fun ready() {
        make()
        queueProm(mock)
        s.init()
        mock.writes().clear()
    }

    @Test
    fun crc4Behaviour() {
        assertEquals(0, Ms5611Minimal.crc4(IntArray(8)))
        val p = prom(false)
        assertEquals(p[7] and 0xF, Ms5611Minimal.crc4(p))
        assertNotEquals(prom(true)[7] and 0xF, Ms5611Minimal.crc4(prom(true)))
    }

    @Test
    fun initSequenceAndProm() {
        make()
        assertTrue(mock.writes().isEmpty())
        queueProm(mock)
        s.init()
        assertEquals(listOf(0x1E, 0xA0, 0xA2, 0xA4, 0xA6, 0xA8, 0xAA, 0xAC, 0xAE), cmds(mock))
        assertArrayEquals(coeffs, s.coefficients())
        assertTrue(s.promCrcOk())
        assertEquals(0x5A3, s.serialCode())
        assertNotNull(Ms5611Minimal(MockConnection()))
    }

    @Test
    fun initRejectsBadCrcAndPropagatesBusError() {
        make()
        queueProm(mock, true)
        assertThrows(IOException::class.java) { s.init() }
        make()
        mock.failNextWrites(1, 0)
        assertThrows(IOException::class.java) { s.init() }
    }

    @Test
    fun datasheetExampleAndSecondOrder() {
        ready()
        var r = s.compensate(d1, d2)
        assertEquals(20.07, r[0], 1e-6)
        assertEquals(100009.0, r[1], 0.5)
        r = s.compensate(d1, d2 - 300000)
        assertEquals(9.55, r[0], 1e-6)
        assertEquals(97972.0, r[1], 0.5)
        r = s.compensate(d1, d2 - 300000, false)
        assertEquals(9.96, r[0], 1e-6)
        assertEquals(98061.0, r[1], 0.5)
        r = s.compensate(d1, d2 - 1200000)
        assertEquals(-27.09, r[0], 1e-6)
        assertEquals(90615.0, r[1], 0.5)
        r = s.compensate(d1, d2 - 1200000, false)
        assertEquals(-20.42, r[0], 1e-6)
        assertEquals(92219.0, r[1], 0.5)
    }

    @Test
    fun minimalReadCommands() {
        make()
        queueProm(mock)
        val m = Ms5611Minimal(mock)
        m.init()
        mock.writes().clear()
        queueAdc(mock, d2)
        queueAdc(mock, d1)
        val r = m.read()
        assertEquals(listOf(0x58, 0x00, 0x48, 0x00), cmds(mock))
        assertEquals(20.07, r[0], 1e-6)
        assertEquals(100009.0, r[1], 0.5)
        queueAdc(mock, 0)
        assertThrows(IOException::class.java) { m.read() }
    }

    @Test
    fun singleValuesAndRaw() {
        ready()
        queueAdc(mock, d2)
        queueAdc(mock, d1)
        assertEquals(100009.0, s.readPressure(), 0.5)
        mock.writes().clear()
        queueAdc(mock, d2)
        assertEquals(20.07, s.readTemperature(), 1e-6)
        assertEquals(listOf(0x58, 0x00), cmds(mock))
        queueAdc(mock, d2)
        queueAdc(mock, d1)
        assertArrayEquals(intArrayOf(d1, d2), s.readRaw())
    }

    @Test
    fun oversamplingCommands() {
        val osrs = intArrayOf(256, 512, 1024, 2048, 4096)
        for (i in 0 until 5) {
            ready()
            s.setOversampling(osrs[i])
            assertEquals(osrs[i], s.getOversampling())
            queueAdc(mock, d2)
            queueAdc(mock, d1)
            s.read()
            assertEquals(0x50 or (i shl 1), cmds(mock)[0])
            assertEquals(0x40 or (i shl 1), cmds(mock)[2])
        }
        make()
        assertThrows(IllegalArgumentException::class.java) { s.setOversampling(300) }
        assertEquals(4096, s.getOversampling())
    }

    @Test
    fun conversionTimes() {
        make()
        assertEquals(0.0006, s.conversionTime(256), 1e-9)
        assertEquals(0.00117, s.conversionTime(512), 1e-9)
        assertEquals(0.00228, s.conversionTime(1024), 1e-9)
        assertEquals(0.00454, s.conversionTime(2048), 1e-9)
        assertEquals(0.00904, s.conversionTime(4096), 1e-9)
        assertEquals(0.00904, s.conversionTime(), 1e-9)
        assertThrows(IllegalArgumentException::class.java) { s.conversionTime(100) }
    }

    @Test
    fun nonBlockingConversion() {
        ready()
        s.startConversion(Ms5611Full.TEMPERATURE)
        s.startConversion(Ms5611Full.PRESSURE, 512)
        assertEquals(listOf(0x58, 0x42), cmds(mock))
        assertThrows(IllegalArgumentException::class.java) { s.startConversion(0, 99) }
        queueAdc(mock, d1)
        assertEquals(d1, s.readAdc())
        queueAdc(mock, 0)
        assertThrows(IOException::class.java) { s.readAdc() }
    }

    @Test
    fun resetAndReadProm() {
        ready()
        queueProm(mock)
        s.reset()
        assertEquals(0x1E, cmds(mock)[0])
        assertEquals(9, mock.writes().size)
        queueProm(mock)
        assertArrayEquals(prom(false), s.readProm())
    }

    @Test
    fun altitudeFormula() {
        ready()
        queueAdc(mock, d2)
        queueAdc(mock, d1)
        val expected = 44330.0 * (1.0 - Math.pow(100009.0 / 101325.0, 1.0 / 5.255))
        val h = s.altitude()
        assertEquals(expected, h, 1e-6)
        assertTrue(h > 0)
        queueAdc(mock, d2)
        queueAdc(mock, d1)
        assertEquals(0.0, s.altitude(100009.0), 1e-6)
    }
}
