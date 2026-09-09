package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Bmp280Test {

    private fun preloadCalibration(connection: MockConnection) {
        // Spec's Data Conversion "Validation" worked example (datasheet page 23):
        // dig_T1=27504, dig_T2=26435, dig_T3=-1000, dig_P1=36477, dig_P2=-10685,
        // dig_P3=3024, dig_P4=2855, dig_P5=140, dig_P6=-7, dig_P7=15500,
        // dig_P8=-14600, dig_P9=6000. Calibration NVM is little-endian.
        connection.setRegister(Bmp280Minimal.REG_CALIB,
            0x70, 0x6B, // dig_T1 = 27504
            0x43, 0x67, // dig_T2 = 26435
            0x18, 0xFC, // dig_T3 = -1000
            0x7D, 0x8E, // dig_P1 = 36477
            0x43, 0xD6, // dig_P2 = -10685
            0xD0, 0x0B, // dig_P3 = 3024
            0x27, 0x0B, // dig_P4 = 2855
            0x8C, 0x00, // dig_P5 = 140
            0xF9, 0xFF, // dig_P6 = -7
            0x8C, 0x3C, // dig_P7 = 15500
            0xF8, 0xC6, // dig_P8 = -14600
            0x70, 0x17) // dig_P9 = 6000
    }

    private fun preloadData(connection: MockConnection) {
        // UT=519888, UP=415148 (same worked example), one 6-byte burst.
        connection.setRegister(Bmp280Minimal.REG_DATA,
            0x65, 0x5A, 0xC0, // adc_P = 415148
            0x7E, 0xED, 0x00) // adc_T = 519888
    }

    @Test
    fun fullApi() {
        val connection = MockConnection()
        // Unlike Python/C++/JS/Rust/Go, the Kotlin driver doesn't write
        // default CTRL_MEAS/CONFIG at construction - it only verifies the
        // chip ID and reads calibration; readRawData() writes CTRL_MEAS
        // unconditionally on every call (always forced mode).
        connection.setRegister(Bmp280Minimal.REG_ID, 0x58)
        preloadCalibration(connection)
        preloadData(connection)

        val sensor = Bmp280Full(connection)

        // temperature(): worked example -> T = 25.08 degC.
        assertEquals(25.08, sensor.temperature(), 1e-3)
        val sawForcedTrigger = connection.writes().any { it.size == 2 && (it[0].toInt() and 0xFF) == Bmp280Minimal.REG_CTRL_MEAS && (it[1].toInt() and 0xFF) == 0x25 }
        assertTrue(sawForcedTrigger, "temperature() should trigger forced mode (CTRL_MEAS=0x25)")

        // pressure(): worked example -> p = 25767233/256/100 = 1006.5325... hPa.
        assertEquals(1006.5325390625, sensor.pressure(), 1e-2)

        // chipId(): expect 0x58.
        assertEquals(0x58, sensor.chipId())

        // status(): raw status byte.
        connection.setRegister(Bmp280Minimal.REG_STATUS, 0x09)
        assertEquals(0x09, sensor.status())

        // configure(osrsT=2, osrsP=3, mode=3, filter=2, tSb=4):
        // config=(4<<5)|(2<<2)=0x88; ctrlMeas=(2<<5)|(3<<2)|3=0x4F.
        sensor.configure(2, 3, 3, 2, 4)
        val configureConfig = connection.writes().last { it.size == 2 && (it[0].toInt() and 0xFF) == Bmp280Minimal.REG_CONFIG }
        val configureCtrl = connection.writes().last { it.size == 2 && (it[0].toInt() and 0xFF) == Bmp280Minimal.REG_CTRL_MEAS }
        assertEquals(0x88.toByte(), configureConfig[1])
        assertEquals(0x4F.toByte(), configureCtrl[1])

        // setOversampling(4, 5): mode bits preserved (3) -> ctrlMeas=0x97.
        sensor.setOversampling(4, 5)
        val setOversamplingWrite = connection.writes().last { it.size == 2 && (it[0].toInt() and 0xFF) == Bmp280Minimal.REG_CTRL_MEAS }
        assertEquals(0x97.toByte(), setOversamplingWrite[1])

        // setMode(1): oversampling bits preserved -> ctrlMeas=0x95.
        sensor.setMode(1)
        val setModeWrite = connection.writes().last { it.size == 2 && (it[0].toInt() and 0xFF) == Bmp280Minimal.REG_CTRL_MEAS }
        assertEquals(0x95.toByte(), setModeWrite[1])

        // setFilter(3): standby bits preserved -> config=0x8C.
        sensor.setFilter(3)
        val setFilterWrite = connection.writes().last { it.size == 2 && (it[0].toInt() and 0xFF) == Bmp280Minimal.REG_CONFIG }
        assertEquals(0x8C.toByte(), setFilterWrite[1])

        // setStandby(6): filter bits preserved -> config=0xCC.
        sensor.setStandby(6)
        val setStandbyWrite = connection.writes().last { it.size == 2 && (it[0].toInt() and 0xFF) == Bmp280Minimal.REG_CONFIG }
        assertEquals(0xCC.toByte(), setStandbyWrite[1])

        // altitude(): pressure() re-triggers + re-reads the same DATA bytes.
        assertEquals(56.07668235692459, sensor.altitude(), 0.5)

        // seaLevelPressure(altitudeM=200)
        assertEquals(1030.736388797547, sensor.seaLevelPressure(200.0), 0.5)

        // reset(): writes soft-reset command, re-reads calibration, re-applies
        // current ctrlMeas/config (0x95, 0xCC from above).
        preloadCalibration(connection)
        sensor.reset()
        val sawSoftReset = connection.writes().any { it.size == 2 && (it[0].toInt() and 0xFF) == Bmp280Minimal.REG_SOFT_RST && (it[1].toInt() and 0xFF) == 0xB6 }
        assertTrue(sawSoftReset, "reset should write the soft-reset command")
        val calReads = connection.writes().count { it.size == 1 && (it[0].toInt() and 0xFF) == Bmp280Minimal.REG_CALIB }
        assertTrue(calReads >= 2, "reset should re-read calibration")
        val reappliedConfig = connection.writes().last { it.size == 2 && (it[0].toInt() and 0xFF) == Bmp280Minimal.REG_CONFIG }
        val reappliedCtrl = connection.writes().last { it.size == 2 && (it[0].toInt() and 0xFF) == Bmp280Minimal.REG_CTRL_MEAS }
        assertEquals(0xCC.toByte(), reappliedConfig[1])
        assertEquals(0x95.toByte(), reappliedCtrl[1])
    }

    @Test
    fun spiMasksWriteAddresses() {
        // Per specs/pressure/bmp280.md's SPI Register-address protocol:
        // BMP280's I2C register addresses already have bit 7 set (0x88-0xFC),
        // so SPI reads use the same value unmasked; only writes differ,
        // clearing bit 7 (reg and 0x7F).
        val connection = MockConnection()
        connection.setRegister(Bmp280Minimal.REG_ID, 0x58)
        preloadCalibration(connection)
        preloadData(connection)

        val sensor = Bmp280Full(connection, 0x76, Bmp280Minimal.BUS_SPI)

        // temperature() triggers a forced-mode write to CTRL_MEAS; on SPI the
        // write address must have bit 7 cleared (0xF4 and 0x7F = 0x74).
        assertEquals(25.08, sensor.temperature(), 1e-3)
        val sawMaskedWrite = connection.writes().any { it.size == 2 && (it[0].toInt() and 0xFF) == (Bmp280Minimal.REG_CTRL_MEAS and 0x7F) }
        assertTrue(sawMaskedWrite, "spi write should use masked CTRL_MEAS address (0x74)")
        val sawUnmaskedWrite = connection.writes().any { it.size == 2 && (it[0].toInt() and 0xFF) == Bmp280Minimal.REG_CTRL_MEAS }
        assertTrue(!sawUnmaskedWrite, "spi should never write the unmasked CTRL_MEAS address (0xF4)")

        // Reads stay unmasked - pressure() still works via the same register
        // constants used for I2C.
        assertEquals(1006.5325390625, sensor.pressure(), 1e-2)

        // configure() also routes through writeReg, so CONFIG/CTRL_MEAS
        // writes stay masked.
        sensor.configure(2, 3, 3, 2, 4)
        val sawMaskedConfig = connection.writes().any { it.size == 2 && (it[0].toInt() and 0xFF) == (Bmp280Minimal.REG_CONFIG and 0x7F) }
        assertTrue(sawMaskedConfig, "spi configure() should mask the CONFIG write address")
        val sawUnmaskedConfig = connection.writes().any { it.size == 2 && (it[0].toInt() and 0xFF) == Bmp280Minimal.REG_CONFIG }
        assertTrue(!sawUnmaskedConfig, "spi should never write the unmasked CONFIG address (0xF5)")
    }
}
