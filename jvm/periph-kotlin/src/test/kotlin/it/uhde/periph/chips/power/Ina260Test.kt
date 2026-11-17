package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Ina260Test {

    private fun assertWrite(w: ByteArray, reg: Int, hi: Int, lo: Int) {
        assertEquals(3, w.size)
        assertEquals(reg.toByte(), w[0])
        assertEquals(hi.toByte(), w[1])
        assertEquals(lo.toByte(), w[2])
    }

    @Test
    fun fullApi() {
        val connection = MockConnection()
        val last = { connection.writes().last() }

        // Construction makes no bus access: no calibration, power-on state is the default.
        val sensor = Ina260Full(connection)
        assertTrue(connection.writes().isEmpty())

        // Bus voltage: raw=6400 (0x1900) -> 8.0 V
        connection.setRegister(Ina260Minimal.REG_BUS, 0x19, 0x00)
        assertEquals(8.0, sensor.voltage())

        // Current: raw signed = -100 (0xFF9C) -> -0.125 A
        connection.setRegister(Ina260Minimal.REG_CURRENT, 0xFF, 0x9C)
        assertEquals(-0.125, sensor.current(), 1e-12)

        // Current: datasheet example 0x2710 -> 12.5 A
        connection.setRegister(Ina260Minimal.REG_CURRENT, 0x27, 0x10)
        assertEquals(12.5, sensor.current(), 1e-12)

        // Power: datasheet example 0x3A7F = 14975 -> 149.75 W
        connection.setRegister(Ina260Minimal.REG_POWER, 0x3A, 0x7F)
        assertEquals(149.75, sensor.power(), 1e-9)

        // configure(2, 3, 5, 6) -> 0x6000|(2<<9)|(3<<6)|(5<<3)|6 = 0x64EE
        sensor.configure(2, 3, 5, 6)
        assertWrite(last(), Ina260Minimal.REG_CONFIG, 0x64, 0xEE)

        // trigger(): same settings, MODE = 3 -> 0x64EB
        sensor.trigger()
        assertWrite(last(), Ina260Minimal.REG_CONFIG, 0x64, 0xEB)

        // conversionReady(): CVRF bit (0x0008)
        connection.setRegister(Ina260Minimal.REG_MASK_EN, 0x00, 0x08)
        assertTrue(sensor.conversionReady())
        connection.setRegister(Ina260Minimal.REG_MASK_EN, 0x00, 0x00)
        assertFalse(sensor.conversionReady())

        // overflow(): OVF bit (0x0004)
        connection.setRegister(Ina260Minimal.REG_MASK_EN, 0x00, 0x04)
        assertTrue(sensor.overflow())

        // setAlert(POL, 1.5 W, polarity, latch): limit 150 (0x0096) first, then mask 0x0803
        connection.writes().clear()
        sensor.setAlert(Ina260Full.POL, 1.5, true, true)
        assertEquals(2, connection.writes().size)
        assertWrite(connection.writes()[0], Ina260Minimal.REG_ALERT_LIM, 0x00, 0x96)
        assertWrite(connection.writes()[1], Ina260Minimal.REG_MASK_EN, 0x08, 0x03)

        // setAlert(OCL, 5.0 A): 4000 (0x0FA0), mask 0x8000
        connection.writes().clear()
        sensor.setAlert(Ina260Full.OCL, 5.0)
        assertWrite(connection.writes()[0], Ina260Minimal.REG_ALERT_LIM, 0x0F, 0xA0)
        assertWrite(connection.writes()[1], Ina260Minimal.REG_MASK_EN, 0x80, 0x00)

        // setAlert(UCL, -1.0 A): -800 -> 0xFCE0
        connection.writes().clear()
        sensor.setAlert(Ina260Full.UCL, -1.0)
        assertWrite(connection.writes()[0], Ina260Minimal.REG_ALERT_LIM, 0xFC, 0xE0)

        // setAlert(CNVR): limit register not written
        connection.writes().clear()
        sensor.setAlert(Ina260Full.CNVR, 0.0)
        assertEquals(1, connection.writes().size)
        assertWrite(connection.writes()[0], Ina260Minimal.REG_MASK_EN, 0x04, 0x00)

        // clearAlert(): Mask/Enable = 0
        sensor.clearAlert()
        assertWrite(last(), Ina260Minimal.REG_MASK_EN, 0x00, 0x00)

        // alertFlags(): raw Mask/Enable register
        connection.setRegister(Ina260Minimal.REG_MASK_EN, 0x08, 0x03)
        assertEquals(0x0803, sensor.alertFlags())

        // reset(): CONFIG = 0x8000
        sensor.reset()
        assertWrite(last(), Ina260Minimal.REG_CONFIG, 0x80, 0x00)

        // shutdown(): MODE = 000 with default settings -> 0x6120
        sensor.shutdown()
        assertWrite(last(), Ina260Minimal.REG_CONFIG, 0x61, 0x20)

        // wake(): restore MODE = 111 -> 0x6127
        sensor.wake()
        assertWrite(last(), Ina260Minimal.REG_CONFIG, 0x61, 0x27)

        // manufacturerId() / dieId()
        connection.setRegister(Ina260Minimal.REG_MFR_ID, 0x54, 0x49)
        assertEquals(0x5449, sensor.manufacturerId())
        connection.setRegister(Ina260Minimal.REG_DIE_ID, 0x22, 0x70)
        assertEquals(0x2270, sensor.dieId())
    }
}
