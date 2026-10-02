package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

// Register addresses are hardcoded hex literals throughout this file rather
// than Lps22dfMinimal.REG_* / CHIP_ID: those companion constants are
// `protected`, and Kotlin's protected is subclass-only (unlike Java/Groovy's
// package+subclass) -- a plain top-level test class here has no access.

private fun newConnection(): MockConnection {
    val c = MockConnection()
    c.setRegister(0x0F, 0xB4)
    return c
}

private fun lastWriteTo(connection: MockConnection, reg: Int): Int {
    for (w in connection.writes().asReversed()) {
        if (w.size == 2 && (w[0].toInt() and 0xFF) == reg) return w[1].toInt() and 0xFF
    }
    return -1
}

class Lps22dfTest {

    @Test
    fun constructionSequence() {
        val connection = newConnection()
        Lps22dfMinimal(connection)

        val writes = connection.writes()
        assertEquals(4, writes.size)
        assertEquals(0x0F, writes[0][0].toInt() and 0xFF)
        assertEquals(0x04, writes[1][1].toInt() and 0xFF) // SWRESET
        assertEquals(3 shl 3, writes[2][1].toInt() and 0xFF) // CTRL_REG1 default
        assertEquals(0x08, writes[3][1].toInt() and 0xFF) // BDU
    }

    @Test
    fun constructionBadChipIdThrows() {
        val connection = MockConnection()
        connection.setRegister(0x0F, 0x00)
        assertThrows(IOException::class.java) { Lps22dfMinimal(connection) }
    }

    @Test
    fun pressureAndTemperature() {
        val connection = newConnection()
        val chip = Lps22dfMinimal(connection)

        connection.setRegister(0x27, 0x01 or 0x02)
        connection.setRegister(0x28, 0x00, 0x80, 0x0C) // raw=819200 -> 20000 Pa
        assertEquals(20000.0, chip.pressure(), 1e-3)

        connection.setRegister(0x2B, 0x2E, 0x09) // raw=2350 -> 23.5 degC
        assertEquals(23.5, chip.temperature(), 1e-3)

        connection.setRegister(0x28, 0x00, 0xC0, 0xF9) // raw=-409600 -> -10000 Pa
        assertEquals(-10000.0, chip.pressure(), 1e-3)

        connection.setRegister(0x2B, 0x0C, 0xFE) // raw=-500 -> -5.0 degC
        assertEquals(-5.0, chip.temperature(), 1e-3)
    }

    // Regression: temperature() must poll STATUS.T_DA before reading TEMP_OUT,
    // exactly like pressure() polls STATUS.P_DA -- it previously read
    // TEMP_OUT_L/H unconditionally with no STATUS check at all.
    @Test
    fun temperaturePollsStatusFirst() {
        val connection = newConnection()
        val chip = Lps22dfMinimal(connection)

        connection.setRegister(0x27, 0x02)
        connection.setRegister(0x2B, 0x2E, 0x09)
        chip.temperature()

        val writes = connection.writes()
        val n = writes.size
        assertEquals(0x27, writes[n - 2][0].toInt() and 0xFF)
        assertEquals(0x2B, writes[n - 1][0].toInt() and 0xFF)
    }

    @Test
    fun configureWritesRegisters() {
        val connection = newConnection()
        val full = Lps22dfFull(connection)
        full.configure(Lps22dfFull.ODR_50_HZ, Lps22dfFull.AVG_16, true, 1, true)
        assertEquals((Lps22dfFull.ODR_50_HZ shl 3) or Lps22dfFull.AVG_16, lastWriteTo(connection, 0x10))
        assertEquals(0x10 or 0x20 or 0x08, lastWriteTo(connection, 0x11))
    }

    @Test
    fun oneshotWritesSequenceAndWaits() {
        val connection = newConnection()
        val full = Lps22dfFull(connection)
        connection.setRegister(0x27, 0x01)
        full.oneshot()

        val writes = connection.writes()
        val n = writes.size
        assertEquals(0x10, writes[n - 3][0].toInt() and 0xFF)
        assertEquals(0x00, writes[n - 3][1].toInt() and 0xFF)
        assertEquals(0x11, writes[n - 2][0].toInt() and 0xFF)
        assertEquals(0x09, writes[n - 2][1].toInt() and 0xFF)
        assertEquals(0x27, writes[n - 1][0].toInt() and 0xFF)
    }

    @Test
    fun altitudeAtZeroPressure() {
        val connection = newConnection()
        val full = Lps22dfFull(connection)
        connection.setRegister(0x27, 0x01)
        connection.setRegister(0x28, 0x00, 0x00, 0x00) // raw=0 -> 0 Pa
        assertEquals(44330.0, full.altitude(101325.0), 1.0)
    }

    @Test
    fun softwareResetWritesCtrlReg2() {
        val connection = newConnection()
        val full = Lps22dfFull(connection)
        full.softwareReset()
        assertEquals(0x04, lastWriteTo(connection, 0x11))
    }

    @Test
    fun setPressureOffset() {
        val connection = newConnection()
        val full = Lps22dfFull(connection)
        // -12.34 hPa -> raw = round(-12.34*4096) = -50545 -> wraps to 14991 (0x3A8F).
        full.setPressureOffset(-1234.0) // Pa
        assertEquals(0x8F, lastWriteTo(connection, 0x1A))
        assertEquals(0x3A, lastWriteTo(connection, 0x1B))
    }

    @Test
    fun setPressureThreshold() {
        val connection = newConnection()
        val full = Lps22dfFull(connection)
        // 900.0 hPa -> raw = round(900*16) & 0x7FFF = 14400 = 0x3840.
        full.setPressureThreshold(90000.0) // Pa
        assertEquals(0x40, lastWriteTo(connection, 0x0C))
        assertEquals(0x38, lastWriteTo(connection, 0x0D))
    }

    @Test
    fun configureInterrupt() {
        val connection = newConnection()
        val full = Lps22dfFull(connection)
        full.configureInterrupt(true, true, true, true, true, true, true, true)
        assertEquals(0x08 or 0x02 or 0x01, lastWriteTo(connection, 0x12))
        assertEquals(0x40 or 0x20 or 0x10 or 0x04 or 0x02 or 0x01, lastWriteTo(connection, 0x13))
    }

    @Test
    fun configurePressureEvent() {
        val connection = newConnection()
        val full = Lps22dfFull(connection)
        full.configurePressureEvent(true, true, true)
        assertEquals(0x01 or 0x02 or 0x04, lastWriteTo(connection, 0x0B))
    }

    @Test
    fun autozeroAutorefpResetReference() {
        val connection = newConnection()
        val full = Lps22dfFull(connection)

        full.autozero()
        assertEquals(0x20, lastWriteTo(connection, 0x0B))

        full.autorefp()
        assertEquals(0x80, lastWriteTo(connection, 0x0B))

        full.resetReference()
        assertEquals(0x50, lastWriteTo(connection, 0x0B))
    }

    @Test
    fun referencePressure() {
        val connection = newConnection()
        val full = Lps22dfFull(connection)
        connection.setRegister(0x16, 0x00, 0x10) // raw=4096 -> 100.0 Pa
        assertEquals(100.0, full.referencePressure(), 1e-3)
    }

    @Test
    fun fifoModeAndWatermark() {
        val connection = newConnection()
        val full = Lps22dfFull(connection)

        full.setFifoMode(Lps22dfFull.FIFO_CONT_TO_FIFO)
        assertEquals((1 shl 2) or 3, lastWriteTo(connection, 0x14))

        full.setFifoWatermark(100)
        assertEquals(100, lastWriteTo(connection, 0x15))
    }

    @Test
    fun readFifo() {
        val connection = newConnection()
        val full = Lps22dfFull(connection)
        connection.setRegister(0x25, 2)
        connection.setRegister(
            0x78,
            0x00, 0x80, 0x0C, // sample 0: raw=819200 -> 20000 Pa
            0x00, 0xC0, 0xF9  // sample 1: raw=-409600 -> -10000 Pa
        )

        val out = DoubleArray(4)
        val n = full.readFifo(out)
        assertEquals(2, n)
        assertEquals(20000.0, out[0], 1e-3)
        assertEquals(-10000.0, out[1], 1e-3)
    }

    @Test
    fun readFifoTruncatesToOutBufferLength() {
        val connection = newConnection()
        val full = Lps22dfFull(connection)
        connection.setRegister(0x25, 5)
        connection.setRegister(
            0x78,
            0x00, 0x80, 0x0C, 0x00, 0x80, 0x0C, 0x00, 0x80, 0x0C,
            0x00, 0x80, 0x0C, 0x00, 0x80, 0x0C
        )

        val out = DoubleArray(2)
        assertEquals(2, full.readFifo(out))
    }

    @Test
    fun interruptSource() {
        val connection = newConnection()
        val full = Lps22dfFull(connection)
        connection.setRegister(0x24, 0x85) // BOOT_ON | IA | PH
        assertEquals(0x85, full.interruptSource())
    }
}
