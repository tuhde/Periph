package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

private fun newConnection(): MockConnection {
    val c = MockConnection()
    c.setRegister(0x0F, 0xB4) // WHO_AM_I
    return c
}

private fun lastWriteTo(connection: MockConnection, reg: Int): Int {
    for (w in connection.writes().asReversed()) {
        if (w.size == 2 && (w[0].toInt() and 0xFF) == reg) return w[1].toInt() and 0xFF
    }
    return -1
}

class Lps28dfwTest {

    @Test
    fun constructionAndRead() {
        val connection = newConnection()
        val chip = Lps28dfwMinimal(connection)
        assertEquals(0x18, lastWriteTo(connection, 0x11))
        assertEquals(0x22, lastWriteTo(connection, 0x10))

        connection.setRegister(0x28, 0x00, 0x54, 0x3F) // 1013.25 hPa
        assertEquals(1013.25, chip.readPressure(), 0.001)
        connection.setRegister(0x2B, 0x2E, 0x09) // 23.5 C
        assertEquals(23.5, chip.readTemperature(), 0.001)
    }

    @Test
    fun constructionBadWhoAmIThrows() {
        val connection = MockConnection()
        connection.setRegister(0x0F, 0x00)
        assertThrows(IOException::class.java) { Lps28dfwMinimal(connection) }
    }

    @Test
    fun negativeValues() {
        val connection = newConnection()
        val chip = Lps28dfwMinimal(connection)

        connection.setRegister(0x28, 0x00, 0xE0, 0xFC) // -50.0 hPa
        assertEquals(-50.0, chip.readPressure(), 0.001)
        connection.setRegister(0x2B, 0x18, 0xFC) // -10.0 C
        assertEquals(-10.0, chip.readTemperature(), 0.001)
    }

    @Test
    fun configureAndRead() {
        val connection = newConnection()
        val full = Lps28dfwFull(connection)

        full.configure(Lps28dfwFull.ODR_50_HZ, Lps28dfwFull.AVG_64, 1, true, 1)
        assertEquals(0x78, lastWriteTo(connection, 0x11))
        assertEquals(0x2C, lastWriteTo(connection, 0x10))

        connection.setRegister(0x28, 0x00, 0x80, 0x3E, 0x21, 0x07) // 2000 hPa mode2, 18.25 C
        val r = full.read()
        assertEquals(2000.0, r[0], 0.001)
        assertEquals(18.25, r[1], 0.001)
    }

    @Test
    fun isDataReady() {
        val connection = newConnection()
        val full = Lps28dfwFull(connection)

        connection.setRegister(0x27, 0x01)
        assertTrue(full.isDataReady())
        connection.setRegister(0x27, 0x00)
        assertTrue(!full.isDataReady())
    }

    @Test
    fun readOneshot() {
        val connection = newConnection()
        val full = Lps28dfwFull(connection)

        connection.setRegister(0x10, 0x22) // saved CTRL_REG1 (ODR=4)
        connection.setRegister(0x11, 0x18) // saved CTRL_REG2
        connection.setRegister(0x27, 0x01) // P_DA already set
        connection.setRegister(0x28, 0x00, 0x80, 0x3E)
        connection.setRegister(0x2B, 0xD0, 0x07)

        val r = full.readOneshot()
        assertEquals(1000.0, r[0], 0.001)
        assertEquals(20.0, r[1], 0.001)
        assertEquals(0x22, lastWriteTo(connection, 0x10))
    }

    @Test
    fun setOffsetAndSoftreset() {
        val connection = newConnection()
        val full = Lps28dfwFull(connection)

        full.setOffset(-0.5) // Mode 1 default: -0.5*4096 = -2048 = 0xF800
        assertEquals(0x00, lastWriteTo(connection, 0x1A))
        assertEquals(0xF8, lastWriteTo(connection, 0x1B))

        // CTRL_REG2 is currently 0x18 from construction.
        full.softreset()
        assertEquals(0x1A, lastWriteTo(connection, 0x11))
    }

    @Test
    fun fifoConfigureBypassPassThroughRegression() {
        // Regression: switching directly to a non-bypass mode must still
        // write FIFO_CTRL=0x00 (Bypass) first, per the spec's FIFO reset
        // procedure -- the buggy version only did this when the target
        // mode itself was Bypass.
        val connection = newConnection()
        val full = Lps28dfwFull(connection)

        full.fifoConfigure(Lps28dfwFull.FIFO_CONTINUOUS, 50, true)
        val fifoCtrlWrites = connection.writes()
            .filter { it.size == 2 && (it[0].toInt() and 0xFF) == 0x14 }
            .map { it[1].toInt() and 0xFF }
        assertTrue(fifoCtrlWrites.size >= 2 && fifoCtrlWrites[0] == 0x00,
            "expected a 0x00 bypass write before the final mode write, got $fifoCtrlWrites")
        assertEquals(0x0A, fifoCtrlWrites.last())
        assertEquals(50, lastWriteTo(connection, 0x15))
    }

    @Test
    fun fifoReadAndLevel() {
        val connection = newConnection()
        val full = Lps28dfwFull(connection)

        connection.setRegister(0x78,
            0x00, 0x80, 0x3E, // 1000.0 hPa
            0x00, 0x20, 0x3F, // 1010.0 hPa
            0x00, 0xC0, 0x3F) // 1020.0 hPa
        val samples = full.fifoRead(3)
        assertEquals(3, samples.size)
        assertEquals(1000.0, samples[0], 0.01)
        assertEquals(1010.0, samples[1], 0.01)
        assertEquals(1020.0, samples[2], 0.01)

        connection.setRegister(0x25, 42)
        assertEquals(42, full.fifoLevel())
    }

    @Test
    fun setThreshold() {
        val connection = newConnection()
        val full = Lps28dfwFull(connection)
        connection.setRegister(0x0B, 0x00)

        full.setThreshold(1020.0, high = true, low = true) // Mode 1: 1020*16=16320=0x3FC0
        assertEquals(0xC0, lastWriteTo(connection, 0x0C))
        assertEquals(0x3F, lastWriteTo(connection, 0x0D))
        assertEquals(0x03, lastWriteTo(connection, 0x0B))
    }

    @Test
    fun chipId() {
        val connection = newConnection()
        val full = Lps28dfwFull(connection)
        assertEquals(0xB4, full.chipId())
    }
}
