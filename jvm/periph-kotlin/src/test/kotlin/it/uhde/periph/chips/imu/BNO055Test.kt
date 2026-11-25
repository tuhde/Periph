package it.uhde.periph.chips.imu

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.IntConsumer

class BNO055Test {

    private lateinit var mock: MockConnection
    private lateinit var s: BNO055Full

    private fun le(vararg vals: Int): IntArray {
        val out = IntArray(vals.size * 2)
        vals.forEachIndexed { i, v ->
            out[2 * i] = v and 0xFF
            out[2 * i + 1] = (v shr 8) and 0xFF
        }
        return out
    }

    private fun fresh(): MockConnection = MockConnection().apply {
        setRegister(0x00, 0xA0, 0xFB, 0x32, 0x0F, 0x11, 0x03, 0x15)
    }

    private fun make() {
        mock = fresh()
        s = BNO055Full(mock)
    }

    /** Values written to a register with a single-byte payload, in order. */
    private fun writesTo(reg: Int): List<Int> =
        mock.writes().filter { it.size == 2 && (it[0].toInt() and 0xFF) == reg }.map { it[1].toInt() and 0xFF }

    @Test
    fun initSequence() {
        make()
        val w2 = mock.writes().filter { it.size == 2 }.map { (it[0].toInt() and 0xFF) to (it[1].toInt() and 0xFF) }
        assertTrue(w2.contains(0x3E to 0x00))
        assertTrue(w2.contains(0x07 to 0x00))
        assertTrue(w2.contains(0x3B to 0x86))
        assertEquals(0x3D to 0x0C, w2.last())
    }

    @Test
    fun wrongChipId() {
        val bad = MockConnection()
        bad.setRegister(0x00, 0x55)
        assertThrows(IOException::class.java) { BNO055Minimal(bad) }
    }

    @Test
    fun euler() {
        mock = fresh()
        val m = BNO055Minimal(mock)
        mock.setRegister(0x1A, *le(1414, -900, 450))
        val e = m.euler()
        assertEquals(1414 / 900.0, e[0], 1e-9)
        assertEquals(-1.0, e[1], 1e-9)
        assertEquals(0.5, e[2], 1e-9)
    }

    @Test
    fun vectors() {
        make()
        mock.setRegister(0x20, *le(16384, -8192, 0, 4096))
        assertArrayEquals(doubleArrayOf(1.0, -0.5, 0.0, 0.25), s.quaternion(), 1e-9)
        mock.setRegister(0x28, *le(100, -250, 981))
        assertArrayEquals(doubleArrayOf(1.0, -2.5, 9.81), s.linearAcceleration(), 1e-9)
        mock.setRegister(0x2E, *le(0, 0, 981))
        assertEquals(9.81, s.gravity()[2], 1e-9)
        mock.setRegister(0x08, *le(-100, 200, 300))
        assertArrayEquals(doubleArrayOf(-1.0, 2.0, 3.0), s.acceleration(), 1e-9)
        mock.setRegister(0x0E, *le(160, -32, 16))
        assertArrayEquals(doubleArrayOf(10.0, -2.0, 1.0), s.magneticField(), 1e-9)
        mock.setRegister(0x14, *le(900, 0, -450))
        assertArrayEquals(doubleArrayOf(1.0, 0.0, -0.5), s.angularRate(), 1e-9)
        mock.setRegister(0x34, 0xE7)
        assertEquals(-25, s.temperature())
    }

    @Test
    fun calibrationStatus() {
        make()
        mock.setRegister(0x35, 0xE4)
        assertArrayEquals(intArrayOf(3, 2, 1, 0), s.calibrationStatus())
        assertFalse(s.isCalibrated())
        mock.setRegister(0x35, 0xFF)
        assertTrue(s.isCalibrated())
    }

    @Test
    fun modes() {
        make()
        mock.writes().clear()
        s.setMode(BNO055Full.MODE_IMU)
        assertEquals(listOf(0x00, 0x08), writesTo(0x3D))
        mock.setRegister(0x3D, 0x08)
        assertEquals(8, s.getMode())
        assertThrows(IllegalArgumentException::class.java) { s.setMode(13) }
    }

    @Test
    fun calibrationBlob() {
        make()
        val blob = ByteArray(22) { (it + 1).toByte() }
        for (i in 0 until 22) mock.setRegister(0x55 + i, i + 1)
        mock.writes().clear()
        assertArrayEquals(blob, s.getCalibration())
        assertEquals(listOf(0x00, 0x0C), writesTo(0x3D))
        mock.writes().clear()
        s.setCalibration(blob)
        val data = mock.writes().filter { it.size == 2 && (it[0].toInt() and 0xFF) in 0x55..0x6A }
        assertEquals(22, data.size)
        data.forEachIndexed { i, w ->
            assertEquals(0x55 + i, w[0].toInt() and 0xFF)
            assertEquals(blob[i], w[1])
        }
        assertEquals(listOf(0x00, 0x0C), writesTo(0x3D))
        assertThrows(IllegalArgumentException::class.java) { s.setCalibration(ByteArray(21)) }
    }

    @Test
    fun configuration() {
        make()
        mock.writes().clear()
        s.setAxisRemap(0x21, 0x02)
        assertEquals(listOf(0x21), writesTo(0x41))
        assertEquals(listOf(0x02), writesTo(0x42))
        assertEquals(listOf(0x00, 0x0C), writesTo(0x3D))
        s.setPowerMode("low_power")
        assertEquals(listOf(1), writesTo(0x3E))
        assertThrows(IllegalArgumentException::class.java) { s.setPowerMode("bogus") }
        s.setTemperatureSource("gyroscope")
        assertEquals(listOf(1), writesTo(0x40))
        mock.writes().clear()
        s.useExternalCrystal(true)
        s.useExternalCrystal(false)
        assertEquals(listOf(0x80, 0x00), writesTo(0x3F))
    }

    @Test
    fun selfTestStatusRevisionsUniqueId() {
        make()
        mock.setRegister(0x36, 0x0D)
        mock.writes().clear()
        assertArrayEquals(booleanArrayOf(true, false, true, true), s.selfTest())
        assertEquals(listOf(0x01), writesTo(0x3F))
        assertEquals(listOf(0x00, 0x0C), writesTo(0x3D))
        mock.setRegister(0x39, 1, 3)
        assertArrayEquals(intArrayOf(1, 3), s.systemStatus())
        assertArrayEquals(intArrayOf(0xA0, 0xFB, 0x32, 0x0F, 0x0311, 0x15), s.revisions())
        val uid = ByteArray(16) { it.toByte() }
        for (i in 0 until 16) mock.setRegister(0x50 + i, i)
        mock.writes().clear()
        assertArrayEquals(uid, s.uniqueId())
        assertEquals(listOf(1, 0), writesTo(0x07))
    }

    @Test
    fun interrupts() {
        make()
        mock.setRegister(0x10, 0)
        mock.setRegister(0x0F, 0)
        mock.writes().clear()
        s.enableInterrupt(BNO055Full.SOURCE_ACC_BSX_DRDY or BNO055Full.SOURCE_GYR_DRDY)
        assertEquals(listOf(0x11), writesTo(0x10))
        assertEquals(listOf(0x11), writesTo(0x0F))
        assertEquals(listOf(1, 0), writesTo(0x07))
        mock.setRegister(0x10, 0x11)
        mock.setRegister(0x0F, 0x11)
        mock.writes().clear()
        s.disableInterrupt(BNO055Full.SOURCE_GYR_DRDY)
        assertEquals(listOf(0x01), writesTo(0x10))
        assertEquals(listOf(0x01), writesTo(0x0F))
        mock.setRegister(0x37, 0x41)
        assertEquals(0x41, s.pollInterrupt())
    }

    @Test
    fun pollingFallbackDeliversStatus() {
        make()
        mock.setRegister(0x37, 0x41)
        val seen = AtomicInteger()
        s.onInterrupt(IntConsumer { seen.set(it) })
        Thread.sleep(250)
        s.offInterrupt()
        assertEquals(0x41, seen.get())
    }

    @Test
    fun reset() {
        make()
        mock.writes().clear()
        s.reset()
        assertEquals(0x20, writesTo(0x3F)[0])
        assertEquals(0x0C, writesTo(0x3D).last())
        assertEquals(0x86, writesTo(0x3B)[0])
    }
}
