package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Ad7706Test {

    @Test
    fun initAndMinimalReads() {
        val connection = MockConnection()
        val sensor = Ad7706Minimal(connection, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ)
        assertArrayEquals(byteArrayOf(0x20, 0x0C), connection.writes()[0])
        assertArrayEquals(byteArrayOf(0x10, 0x40), connection.writes()[1])
        assertArrayEquals(byteArrayOf(0x08), connection.writes()[2])

        connection.setRegister(0x38, 0xC0, 0x00)
        assertEquals(0xC000, sensor.readRaw())
        assertTrue(Math.abs(sensor.readVoltage() - 1.25f) < 1e-6f)
    }

    @Test
    fun threeChannelsIndependentState() {
        // Regression tests for driver bugs found while writing this test:
        // configure() only updated the shared gain/bipolar/buffered fields for
        // channel 1, and configureClock() was hardcoded to always write
        // Channel 1's Clock Register.
        val connection = MockConnection()
        val full = Ad7706Full(connection, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ)

        full.configure(2, 4, false, true, 250)
        var n = connection.writes().size
        assertArrayEquals(byteArrayOf(0x21, 0x0E), connection.writes()[n - 2])
        assertArrayEquals(byteArrayOf(0x11, 0x16), connection.writes()[n - 1])

        full.configure(3, 8, true, false, 500)
        n = connection.writes().size
        assertArrayEquals(byteArrayOf(0x23, 0x0F), connection.writes()[n - 2])
        assertArrayEquals(byteArrayOf(0x13, 0x18), connection.writes()[n - 1])

        connection.setRegister(0x39, 0x80, 0x00)
        assertTrue(Math.abs(full.readVoltage(2) - 0.3125f) < 1e-6f)

        connection.setRegister(0x3B, 0xE0, 0x00)
        assertTrue(Math.abs(full.readVoltage(3) - 0.234375f) < 1e-6f)

        connection.setRegister(0x38, 0xC0, 0x00)
        assertTrue(Math.abs(full.readVoltage(1) - 1.25f) < 1e-6f)

        assertThrows(IllegalArgumentException::class.java) { full.configure(4, 1, true, false, 50) }
    }

    @Test
    fun calibrationUsesConfiguredChannelState() {
        val connection = MockConnection()
        val full = Ad7706Full(connection, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ)
        full.configure(3, 8, true, false, 500)

        full.selfCalibrate(3)
        val n = connection.writes().size
        assertArrayEquals(byteArrayOf(0x13, 0x58), connection.writes()[n - 2])
    }

    @Test
    fun calibrationRegistersAndPowerControl() {
        val connection = MockConnection()
        val full = Ad7706Full(connection, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ)

        connection.setRegister(0x6B, 0x12, 0x34, 0x56)
        assertEquals(0x123456, full.getOffsetCalibration(3))

        full.setOffsetCalibration(0xABCDEF, 3)
        assertArrayEquals(
            byteArrayOf(0x63, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()),
            connection.writes().last(),
        )

        full.standby()
        assertArrayEquals(byteArrayOf(0x04), connection.writes().last())

        full.wakeup()
        val n = connection.writes().size
        assertArrayEquals(byteArrayOf(0x00), connection.writes()[n - 2])
        assertArrayEquals(byteArrayOf(0x08), connection.writes()[n - 1])
    }

    @Test
    fun reset() {
        val connection = MockConnection()
        val full = Ad7706Full(connection, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ)
        assertThrows(IllegalStateException::class.java) { full.reset() }

        val calls = mutableListOf<Boolean>()
        val pin = Ad7706ResetPin { high -> calls.add(high) }
        val connection2 = MockConnection()
        val withReset = Ad7706Full(connection2, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ, pin)

        withReset.reset()
        assertEquals(listOf(false, true), calls)
    }
}
