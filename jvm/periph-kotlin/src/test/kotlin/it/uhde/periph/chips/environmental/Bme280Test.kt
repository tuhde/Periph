package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class Bme280Test {

    private fun assertClose(expected: Double, actual: Double, tol: Double) {
        assertTrue(abs(actual - expected) < tol, "expected ~$expected but was $actual")
    }

    @Test
    fun fullApi() {
        val connection = MockConnection()
        connection.setRegister(Bme280Minimal.REG_ID, 0x60)
        // Calibration NVM block 1 (26 bytes from 0x88), BMP280 datasheet worked
        // example, plus digH1=75 at 0xA1.
        connection.setRegister(
            Bme280Minimal.REG_CALIB,
            0x70, 0x6B, 0x43, 0x67, 0x18, 0xFC, 0x7D, 0x8E, 0x43, 0xD6, 0xD0,
            0x0B, 0x27, 0x0B, 0x8C, 0x00, 0xF9, 0xFF, 0x8C, 0x3C, 0xF8, 0xC6,
            0x70, 0x17, 0x00, 0x4B
        )
        // Calibration NVM block 2 (7 bytes from 0xE1): digH2=384, digH3=0,
        // digH4=301, digH5=50, digH6=30.
        connection.setRegister(Bme280Minimal.REG_CAL_H2, 0x80, 0x01, 0x00, 0x12, 0x2D, 0x03, 0x1E)
        // ADC burst (8 bytes from 0xF7): adcP=415148, adcT=519888, adcH=32768.
        connection.setRegister(Bme280Minimal.REG_DATA, 0x65, 0x5A, 0xC0, 0x7E, 0xED, 0x00, 0x80, 0x00)

        val expectedT = 25.08
        val expectedP = 1006.5325390625
        val expectedH = 79.0869140625

        val sensor = Bme280Full(connection)

        // This driver's default ctrlMeas (0x25) already encodes forced mode
        // (osrs_t=x1, osrs_p=x1, mode=1), written directly at construction.
        assertEquals(0x01, connection.registers()[Bme280Minimal.REG_CTRL_HUM])
        assertEquals(0x25, connection.registers()[Bme280Minimal.REG_CTRL_MEAS])
        assertEquals(0x00, connection.registers()[Bme280Minimal.REG_CONFIG])

        assertClose(expectedT, sensor.temperature(), 0.01)
        assertClose(expectedP, sensor.pressure(), 0.01)
        assertClose(expectedH, sensor.humidity(), 0.01)

        val lastCtrlMeas = connection.registers()[Bme280Minimal.REG_CTRL_MEAS]!!
        assertEquals(1, lastCtrlMeas and 0x03)

        sensor.configure(2, 3, 1, 3, 2, 5)
        assertEquals(1, connection.registers()[Bme280Minimal.REG_CTRL_HUM])
        assertEquals((5 shl 5) or (2 shl 2), connection.registers()[Bme280Minimal.REG_CONFIG])
        assertEquals((2 shl 5) or (3 shl 2) or 3, connection.registers()[Bme280Minimal.REG_CTRL_MEAS])

        sensor.setOversampling(3, 4, 2)
        assertEquals(2, connection.registers()[Bme280Minimal.REG_CTRL_HUM])
        assertEquals((3 shl 5) or (4 shl 2) or 3, connection.registers()[Bme280Minimal.REG_CTRL_MEAS])

        sensor.setMode(1)
        assertEquals((3 shl 5) or (4 shl 2) or 1, connection.registers()[Bme280Minimal.REG_CTRL_MEAS])

        sensor.setFilter(3)
        assertEquals((5 shl 5) or (3 shl 2), connection.registers()[Bme280Minimal.REG_CONFIG])

        sensor.setStandby(6)
        assertEquals((6 shl 5) or (3 shl 2), connection.registers()[Bme280Minimal.REG_CONFIG])

        connection.setRegister(Bme280Minimal.REG_STATUS, 0x08)
        assertEquals(0x08, sensor.status())

        assertClose(56.07668235692459, sensor.altitude(1013.25), 0.05)
        assertClose(1013.25, sensor.seaLevelPressure(56.07668235692459), 0.05)
        assertClose(21.191706255732008, sensor.dewPoint(), 0.05)

        assertEquals(0x60, sensor.chipId())

        sensor.reset()
        val sawReset = connection.writes().any {
            it.size == 2 && (it[0].toInt() and 0xFF) == Bme280Minimal.REG_SOFT_RST &&
                (it[1].toInt() and 0xFF) == Bme280Minimal.RESET_CMD
        }
        assertTrue(sawReset, "expected a soft-reset command write")
        assertEquals(2, connection.registers()[Bme280Minimal.REG_CTRL_HUM])
        assertEquals((6 shl 5) or (3 shl 2), connection.registers()[Bme280Minimal.REG_CONFIG])
        assertEquals((3 shl 5) or (4 shl 2) or 1, connection.registers()[Bme280Minimal.REG_CTRL_MEAS])
    }
}
