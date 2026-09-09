package it.uhde.periph.chips.gas

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Ens160Test {

    @Test
    fun fullApi() {
        val connection = MockConnection()
        // PART_ID read during construction must return 0x0160 (LE: 0x60, 0x01).
        connection.setRegister(Ens160Minimal.REG_PART_ID, 0x60, 0x01)

        val sensor = Ens160Full(connection)

        val opmodeWrites = connection.writes().filter { it.size == 2 && (it[0].toInt() and 0xFF) == Ens160Minimal.REG_OPMODE }
        assertTrue(
            opmodeWrites.first()[1] == Ens160Minimal.OPMODE_IDLE.toByte() &&
                opmodeWrites.last()[1] == Ens160Minimal.OPMODE_STANDARD.toByte(),
            "init should write IDLE then STANDARD"
        )

        // DEVICE_STATUS: NEWDAT (bit1) set, VALIDITY_FLAG (bits 3:2) = 0 (OK).
        connection.setRegister(Ens160Minimal.REG_DEVICE_STATUS, 0x02)
        assertEquals(Ens160Full.VALIDITY_OK, sensor.status())

        // DATA_AQI block (5 bytes): aqi=2, tvocPpb=150 (0x0096 LE), eco2Ppm=500 (0x01F4 LE).
        connection.setRegister(Ens160Minimal.REG_DATA_AQI, 2, 0x96, 0x00, 0xF4, 0x01)

        val data = sensor.readAirQuality()
        assertArrayEquals(doubleArrayOf(2.0, 150.0, 500.0), data)

        assertEquals(150.0, sensor.readTvoc())
        assertEquals(500.0, sensor.readEco2())
        assertEquals(2, sensor.readAqi())
        assertEquals(150.0, sensor.readEthanol())

        sensor.setCompensation(25.0, 50.0)
        // Kotlin's Double.toInt() truncates (unlike Java's Math.round used elsewhere).
        val tempRaw = ((25.0 + 273.15) * 64).toInt()
        val rhRaw = (50.0 * 512).toInt()
        assertEquals(tempRaw and 0xFF, connection.registers()[Ens160Minimal.REG_TEMP_IN])
        assertEquals((tempRaw shr 8) and 0xFF, connection.registers()[Ens160Minimal.REG_TEMP_IN + 1])
        assertEquals(rhRaw and 0xFF, connection.registers()[Ens160Minimal.REG_RH_IN])
        assertEquals((rhRaw shr 8) and 0xFF, connection.registers()[Ens160Minimal.REG_RH_IN + 1])

        // DATA_T/DATA_RH actuals (4 bytes): tempRaw=0x5202 (~55.0 degC), rhRaw=0x6400 (50.0 %RH).
        connection.setRegister(Ens160Minimal.REG_DATA_T, 0x02, 0x52, 0x00, 0x64)
        val actuals = sensor.readCompensationActuals()
        assertEquals((0x5202 / 64.0) - 273.15, actuals[0], 1e-9)
        assertEquals(0x6400 / 512.0, actuals[1], 1e-9)

        // GPR_READ sensor 1 (offset 0): raw=2048 -> resistance = 2**(2048/2048) = 2.0 Ohm.
        connection.setRegister(Ens160Minimal.REG_GPR_READ, 0x00, 0x08)
        assertEquals(2.0, sensor.readRawResistance(1))

        // GPR_READ sensor 4 (offset 6): raw=4096 -> resistance = 2**(4096/2048) = 4.0 Ohm.
        connection.setRegister(Ens160Minimal.REG_GPR_READ + 6, 0x00, 0x10)
        assertEquals(4.0, sensor.readRawResistance(4))

        assertThrows(IllegalArgumentException::class.java) { sensor.readRawResistance(2) }

        // GET_APPVER response at GPR_READ+4: major=1, minor=2, release=3.
        connection.setRegister(Ens160Minimal.REG_GPR_READ + 4, 1, 2, 3)
        assertArrayEquals(intArrayOf(1, 2, 3), sensor.getFirmwareVersion())

        sensor.configureInterrupt(enabled = true, activeHigh = true, pushPull = true, onData = true, onGpr = true)
        val configWrites = connection.writes().filter { it.size == 2 && (it[0].toInt() and 0xFF) == Ens160Minimal.REG_CONFIG }
        assertEquals(0x6B.toByte(), configWrites.last()[1])

        sensor.sleep()
        assertEquals(Ens160Minimal.OPMODE_DEEP_SLEEP.toByte(), connection.writes().last()[1])

        sensor.wake()
        assertEquals(Ens160Minimal.OPMODE_STANDARD.toByte(), connection.writes().last()[1])
    }
}
