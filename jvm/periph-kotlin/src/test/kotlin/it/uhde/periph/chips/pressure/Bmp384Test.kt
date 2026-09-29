package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

// Arbitrary but fixed calibration NVM block (21 bytes at 0x31).
// NVM: T1=27664, T2=27728, T3=3, P1=-4079, P2=802, P3=-8, P4=5, P5=32832,
//      P6=7696, P7=-16, P8=10, P9=4064, P10=-5, P11=2.
private fun preloadCalibration(connection: MockConnection) {
    connection.setRegister(0x31,
        0x10, 0x6C, // T1 u16 LE
        0x50, 0x6C, // T2 u16 LE
        0x03,       // T3 s8
        0x11, 0xF0, // P1 s16 LE
        0x22, 0x03, // P2 s16 LE
        0xF8,       // P3 s8
        0x05,       // P4 s8
        0x40, 0x80, // P5 u16 LE
        0x10, 0x1E, // P6 u16 LE
        0xF0,       // P7 s8
        0x0A,       // P8 s8
        0xE0, 0x0F, // P9 s16 LE
        0xFB,       // P10 s8
        0x02)       // P11 s8
    connection.setRegister(0x00, 0x50) // CHIP_ID
}

private fun newConnection(): MockConnection {
    val c = MockConnection()
    preloadCalibration(c)
    return c
}

private fun lastWriteTo(connection: MockConnection, reg: Int): Int {
    for (w in connection.writes().asReversed()) {
        if (w.size == 2 && (w[0].toInt() and 0xFF) == reg) return w[1].toInt() and 0xFF
    }
    return -1
}

// uncomp_press=6000000, uncomp_temp=8000000 -> t_lin=23.715563300065696 degC,
// pressure=1447.6955007429672 hPa (computed independently from the same
// Bosch compensation formula; cross-checked across all language ports).
private val PRESS_BYTES = intArrayOf(0x80, 0x8D, 0x5B)
private val TEMP_BYTES = intArrayOf(0x00, 0x12, 0x7A)
private const val EXPECTED_T_LIN = 23.715563300065696
private const val EXPECTED_PRESSURE_HPA = 1447.6955007429672

private fun setBurst(connection: MockConnection) {
    connection.setRegister(0x04,
        PRESS_BYTES[0], PRESS_BYTES[1], PRESS_BYTES[2],
        TEMP_BYTES[0], TEMP_BYTES[1], TEMP_BYTES[2])
}

class Bmp384Test {

    @Test
    fun constructionWritesDefaultConfig() {
        val connection = newConnection()
        Bmp384Minimal(connection)
        assertEquals((1 shl 3) or 4, lastWriteTo(connection, 0x1C))
        assertEquals(2 shl 1, lastWriteTo(connection, 0x1F))
        assertEquals(0x03, lastWriteTo(connection, 0x1D))
        assertEquals((0x03 shl 4) or 0x02 or 0x01, lastWriteTo(connection, 0x1B))
    }

    @Test
    fun constructionBadChipIdThrows() {
        val connection = MockConnection()
        preloadCalibration(connection)
        connection.setRegister(0x00, 0x00) // wrong chip ID
        assertThrows(IOException::class.java) { Bmp384Minimal(connection) }
    }

    @Test
    fun temperatureAndPressure() {
        val connection = newConnection()
        val chip = Bmp384Minimal(connection)

        setBurst(connection)
        assertEquals(EXPECTED_T_LIN, chip.temperature(), 1e-6)

        setBurst(connection)
        assertEquals(EXPECTED_PRESSURE_HPA, chip.pressure(), 1e-6)
    }

    @Test
    fun readCombinedBurst() {
        val connection = newConnection()
        val full = Bmp384Full(connection)
        setBurst(connection)
        val result = full.read()
        assertEquals(EXPECTED_PRESSURE_HPA, result[0], 1e-6)
        assertEquals(EXPECTED_T_LIN, result[1], 1e-6)
    }

    // Regression: read() must trigger a forced measurement exactly like
    // temperature()/pressure()/readForced() do -- it was previously missing
    // this entirely in forced mode.
    @Test
    fun readForcedModeTriggers() {
        val connection = newConnection()
        val full = Bmp384Full(connection)
        full.setMode(Bmp384Minimal.MODE_FORCED)
        setBurst(connection)
        full.read()
        assertEquals((Bmp384Minimal.MODE_FORCED shl 4) or 0x02 or 0x01, lastWriteTo(connection, 0x1B))
    }

    @Test
    fun readForcedRestoresMode() {
        val connection = newConnection()
        val full = Bmp384Full(connection)
        setBurst(connection)
        val result = full.readForced()
        assertEquals(EXPECTED_PRESSURE_HPA, result[0], 1e-6)
        assertEquals((Bmp384Minimal.MODE_NORMAL shl 4) or 0x02 or 0x01, lastWriteTo(connection, 0x1B))
    }

    @Test
    fun setModeWritesPwrCtrl() {
        val connection = newConnection()
        val full = Bmp384Full(connection)
        full.setMode(Bmp384Minimal.MODE_SLEEP)
        assertEquals((Bmp384Minimal.MODE_SLEEP shl 4) or 0x02 or 0x01, lastWriteTo(connection, 0x1B))
    }

    @Test
    fun isDataReady() {
        val connection = newConnection()
        val full = Bmp384Full(connection)
        connection.setRegister(0x03, 1 shl 5)
        assertTrue(full.isDataReady())
        connection.setRegister(0x03, 0x00)
        assertFalse(full.isDataReady())
    }

    @Test
    fun softreset() {
        val connection = newConnection()
        val full = Bmp384Full(connection)
        full.softreset()
        assertEquals(0xB6, lastWriteTo(connection, 0x7E))
        assertEquals((Bmp384Minimal.MODE_NORMAL shl 4) or 0x02 or 0x01, lastWriteTo(connection, 0x1B))
    }

    @Test
    fun fifoConfigure() {
        val connection = newConnection()
        val full = Bmp384Full(connection)
        full.fifoConfigure(true, true, 300, true)
        assertEquals((1 shl 4) or (1 shl 3) or (1 shl 1) or 1, lastWriteTo(connection, 0x17))
        assertEquals(300 and 0xFF, lastWriteTo(connection, 0x15))
        assertEquals((300 shr 8) and 0x01, lastWriteTo(connection, 0x16))
    }

    @Test
    fun fifoReadParsesAllFrameTypes() {
        val connection = newConnection()
        val full = Bmp384Full(connection)
        val fifoBytes = intArrayOf(
            0x84, PRESS_BYTES[0], PRESS_BYTES[1], PRESS_BYTES[2], // pressure
            0x90, TEMP_BYTES[0], TEMP_BYTES[1], TEMP_BYTES[2],    // temperature
            0xA0, 0x01, 0x02, 0x03,                               // sensortime
            0x44,                                                 // error
            0x80,                                                 // empty
            0xFF,                                                 // unknown
        )
        connection.setRegister(0x12, fifoBytes.size and 0xFF, (fifoBytes.size shr 8) and 0x01)
        connection.setRegister(0x14, *fifoBytes)
        // tLin is protected (Kotlin: subclass-only, not accessible from this
        // test), so populate it the same way production code would -- via a
        // real temperature() call -- rather than poking the field directly.
        setBurst(connection)
        full.temperature()

        val frames = full.fifoRead()
        assertEquals(6, frames.size)
        assertEquals("pressure", frames[0].type)
        assertEquals(EXPECTED_PRESSURE_HPA, frames[0].value, 1e-6)
        assertEquals("temperature", frames[1].type)
        assertEquals(EXPECTED_T_LIN, frames[1].value, 1e-6)
        assertEquals("sensortime", frames[2].type)
        assertEquals(0x030201.toDouble(), frames[2].value, 1e-9)
        assertEquals("error", frames[3].type)
        assertEquals("empty", frames[4].type)
        assertEquals("unknown", frames[5].type)
    }

    @Test
    fun fifoReadEmptyFifo() {
        val connection = newConnection()
        val full = Bmp384Full(connection)
        connection.setRegister(0x12, 0x00, 0x00)
        assertEquals(0, full.fifoRead().size)
    }

    @Test
    fun fifoFlush() {
        val connection = newConnection()
        val full = Bmp384Full(connection)
        full.fifoFlush()
        assertEquals(0xB0, lastWriteTo(connection, 0x7E))
    }

    @Test
    fun altitudeNegativeForHighPressure() {
        val connection = newConnection()
        val full = Bmp384Full(connection)
        setBurst(connection)
        // Fixture pressure (1447 hPa) is above the standard sea-level reference.
        assertTrue(full.altitude() < 0)
    }
}
