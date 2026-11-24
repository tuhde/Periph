package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class Bme688Test {

    private fun assertClose(expected: Double, actual: Double, tol: Double) {
        assertTrue(abs(actual - expected) < tol, "expected ~$expected but was $actual")
    }

    @Test
    fun fullApi() {
        val connection = MockConnection()
        connection.setRegister(Bme688Minimal.REG_CHIP_ID, 0x61)
        // Calibration block 1 (23 bytes from 0x8A). No published worked example
        // exists for BME688 - these are self-consistent, hand-derived values
        // used to check every language's translation against the same formula.
        connection.setRegister(
            Bme688Minimal.REG_CALIB_BLOCK1,
            0x43, 0x67, 0x03, 0x00, 0x7D, 0x8E, 0x43, 0xD6, 0x58, 0x00, 0x27,
            0x0B, 0x8C, 0x00, 0x0F, 0xF9, 0x00, 0x00, 0xF8, 0xC6, 0x70, 0x17, 0x1E
        )
        // Calibration block 2 (14 bytes from 0xE1).
        connection.setRegister(
            Bme688Minimal.REG_CALIB_BLOCK2,
            0x2B, 0xC8, 0x25, 0x00, 0x2D, 0x14, 0x78, 0x9C, 0x90, 0x65, 0x0C, 0xE5, 0xE2, 0x1E
        )
        // Single-byte calibration: resHeatVal=50, resHeatRange=2.
        connection.setRegister(Bme688Minimal.REG_RES_HEAT_VAL, 0x32)
        connection.setRegister(Bme688Minimal.REG_RES_HEAT_RNG, 0x20)
        // Field 0 (17 bytes from 0x1D): new_data set, pressAdc=415148, tempAdc=419888,
        // humAdc=20000, gasAdc=400, gasRange=5, gasValid=1, heatStab=1
        // (gas at 0x2C/0x2D, not 0x2A/0x2B as on the BME680).
        val field0 = intArrayOf(
            0x80, 0x00, 0x65, 0x5A, 0xC0, 0x66, 0x83, 0x00, 0x4E, 0x20,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x64, 0x35
        )
        connection.setRegister(Bme688Minimal.REG_DATA, *field0)

        val expectedT = 1.23
        val expectedP = 969.4
        val expectedH = 39.826
        val expectedGas = 2178700.0

        val sensor = Bme688Full(connection)

        assertEquals(0x01, connection.registers()[Bme688Minimal.REG_CTRL_HUM])
        assertEquals((1 shl 5) or (1 shl 2) or 0, connection.registers()[Bme688Minimal.REG_CTRL_MEAS])
        assertEquals(0x00, connection.registers()[Bme688Minimal.REG_CONFIG])
        assertEquals(0x52, connection.registers()[Bme688Minimal.REG_RES_HEAT_BASE])
        assertEquals(0x65, connection.registers()[Bme688Minimal.REG_GAS_WAIT_BASE])
        assertEquals(0x20, connection.registers()[Bme688Minimal.REG_CTRL_GAS_1])

        sensor.setHeater(300, 200)
        assertEquals(0x4E, connection.registers()[Bme688Minimal.REG_RES_HEAT_BASE])
        assertEquals(0x72, connection.registers()[Bme688Minimal.REG_GAS_WAIT_BASE])

        sensor.setHeaterProfile(4, 280, 50)
        assertEquals(0x49, connection.registers()[Bme688Minimal.REG_RES_HEAT_BASE + 4])
        assertEquals(0x32, connection.registers()[Bme688Minimal.REG_GAS_WAIT_BASE + 4])

        assertClose(expectedT, sensor.temperature(), 0.01)
        assertClose(expectedP, sensor.pressure(), 0.1)
        assertClose(expectedH, sensor.humidity(), 0.01)
        assertClose(expectedGas, sensor.gasResistance(), 1.0)

        val lastCtrlMeas = connection.registers()[Bme688Minimal.REG_CTRL_MEAS]!!
        assertEquals(1, lastCtrlMeas and 0x03)

        sensor.configure(2, 3, 1, 0, 3)
        assertEquals(1, connection.registers()[Bme688Minimal.REG_CTRL_HUM])
        assertEquals(3 shl 2, connection.registers()[Bme688Minimal.REG_CONFIG])
        assertEquals((2 shl 5) or (3 shl 2) or 0, connection.registers()[Bme688Minimal.REG_CTRL_MEAS])

        sensor.setOversampling(3, 4, 2)
        assertEquals(2, connection.registers()[Bme688Minimal.REG_CTRL_HUM])
        assertEquals((3 shl 5) or (4 shl 2) or 0, connection.registers()[Bme688Minimal.REG_CTRL_MEAS])

        sensor.setFilter(5)
        assertEquals(5 shl 2, connection.registers()[Bme688Minimal.REG_CONFIG])

        sensor.selectHeaterProfile(2)
        assertEquals(0x20 or 2, connection.registers()[Bme688Minimal.REG_CTRL_GAS_1])

        sensor.setGasEnabled(false)
        assertEquals(2, connection.registers()[Bme688Minimal.REG_CTRL_GAS_1])
        sensor.setGasEnabled(true)
        assertEquals(0x20 or 2, connection.registers()[Bme688Minimal.REG_CTRL_GAS_1])

        sensor.setHeaterOff(true)
        assertEquals(0x08, connection.registers()[Bme688Minimal.REG_CTRL_GAS_0])
        sensor.setHeaterOff(false)
        assertEquals(0x00, connection.registers()[Bme688Minimal.REG_CTRL_GAS_0])

        val all = sensor.readAll()
        assertClose(expectedT, all.temperatureC, 0.01)
        assertClose(expectedP, all.pressureHpa, 0.1)
        assertClose(expectedH, all.humidityPct, 0.01)
        assertClose(expectedGas, all.gasResistanceOhm, 1.0)

        assertTrue(sensor.gasValid())
        assertTrue(sensor.heaterStable())

        assertEquals(0x61, sensor.chipId())
        connection.setRegister(Bme688Minimal.REG_VARIANT_ID, 0x01)
        assertEquals(0x01, sensor.variantId())

        // Parallel mode: two heater steps, field 1 has no new data, field 2 an invalid gas reading.
        sensor.startParallel(intArrayOf(320, 250), intArrayOf(1, 2), 100.0)
        assertEquals(0x52, connection.registers()[Bme688Minimal.REG_RES_HEAT_BASE])
        assertEquals(1, connection.registers()[Bme688Minimal.REG_GAS_WAIT_BASE])
        assertEquals(2, connection.registers()[Bme688Minimal.REG_GAS_WAIT_BASE + 1])
        assertEquals((1 shl 6) or (210 / 4), connection.registers()[0x6E])
        assertEquals(0x20 or 2, connection.registers()[Bme688Minimal.REG_CTRL_GAS_1])
        assertEquals(2, connection.registers()[Bme688Minimal.REG_CTRL_MEAS]!! and 0x03)
        val field2 = field0.copyOf()
        field2[0] = 0x81
        field2[1] = 0x03
        field2[16] = 0x05
        connection.setRegister(Bme688Minimal.REG_DATA, *(field0 + IntArray(17) + field2))
        val fields = sensor.readParallel()
        assertEquals(2, fields.size)
        assertEquals(0, fields[0].step)
        assertEquals(0, fields[0].subIndex)
        assertClose(expectedT, fields[0].temperatureC, 0.01)
        assertClose(expectedGas, fields[0].gasResistanceOhm, 1.0)
        assertTrue(fields[0].gasValid && fields[0].heaterStable)
        assertEquals(1, fields[1].step)
        assertEquals(3, fields[1].subIndex)
        assertTrue(fields[1].gasResistanceOhm.isNaN())
        sensor.stopParallel()
        assertEquals(0, connection.registers()[Bme688Minimal.REG_CTRL_MEAS]!! and 0x03)

        sensor.reset()
        val sawReset = connection.writes().any {
            it.size == 2 && (it[0].toInt() and 0xFF) == Bme688Minimal.REG_SOFT_RESET &&
                (it[1].toInt() and 0xFF) == 0xB6
        }
        assertTrue(sawReset, "expected a soft-reset command write")
        assertEquals(2, connection.registers()[Bme688Minimal.REG_CTRL_HUM])
        assertEquals(5 shl 2, connection.registers()[Bme688Minimal.REG_CONFIG])
        assertEquals((3 shl 5) or (4 shl 2) or 0, connection.registers()[Bme688Minimal.REG_CTRL_MEAS])
        assertEquals(0x20 or 2, connection.registers()[Bme688Minimal.REG_CTRL_GAS_1])
        assertEquals(0x80, sensor.status())
    }
}
