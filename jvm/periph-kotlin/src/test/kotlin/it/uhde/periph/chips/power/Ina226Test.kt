package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Ina226Test {

    @Test
    fun fullApi() {
        val connection = MockConnection()

        // Construction: rShunt=0.1, maxCurrent=2.0 (defaults) -> currentLsb=6.103515625e-5,
        // cal=(0.00512/(currentLsb*0.1)).toInt()=838 (0x0346). Constructor writes CONFIG then CAL.
        val sensor = Ina226Full(connection)

        assertEquals(0x41, connection.registers()[Ina226Minimal.REG_CONFIG])
        assertEquals(0x27, connection.registers()[Ina226Minimal.REG_CONFIG + 1])
        assertEquals(0x03, connection.registers()[Ina226Minimal.REG_CAL])
        assertEquals(0x46, connection.registers()[Ina226Minimal.REG_CAL + 1])

        // Bus voltage: raw=6400 (0x1900) -> 6400 * 1.25e-3 = 8.0 V
        connection.setRegister(Ina226Minimal.REG_BUS, 0x19, 0x00)
        assertEquals(8.0, sensor.voltage())

        // Shunt voltage: raw signed = -100 (0xFF9C) -> -100 * 2.5e-6 V
        connection.setRegister(Ina226Minimal.REG_SHUNT, 0xFF, 0x9C)
        assertEquals(-100 * 2.5e-6, sensor.shuntVoltage(), 1e-12)

        // Current: raw signed = 1000 (0x03E8) -> 1000 * currentLsb
        connection.setRegister(Ina226Minimal.REG_CURRENT, 0x03, 0xE8)
        val currentLsb = 2.0 / 32768.0
        assertEquals(1000 * currentLsb, sensor.current(), 1e-12)

        // Power: raw = 500 (0x01F4) -> 500 * 25 * currentLsb
        connection.setRegister(Ina226Minimal.REG_POWER, 0x01, 0xF4)
        assertEquals(500 * 25.0 * currentLsb, sensor.power(), 1e-9)

        // configure(avg=2, vbusCt=3, vshCt=5, mode=6) -> config = 0x04EE
        sensor.configure(2, 3, 5, 6)
        assertEquals(0x04, connection.registers()[Ina226Minimal.REG_CONFIG])
        assertEquals(0xEE, connection.registers()[Ina226Minimal.REG_CONFIG + 1])

        // conversionReady(): CVRF bit (0x0008)
        connection.setRegister(Ina226Minimal.REG_MASK_EN, 0x00, 0x08)
        assertTrue(sensor.conversionReady())
        connection.setRegister(Ina226Minimal.REG_MASK_EN, 0x00, 0x00)
        assertFalse(sensor.conversionReady())

        // overflow(): OVF bit (0x0004)
        connection.setRegister(Ina226Minimal.REG_MASK_EN, 0x00, 0x04)
        assertTrue(sensor.overflow())

        // setAlert(POL, 1.5): raw = (1.5/(25*currentLsb)).toInt() = 983 (0x03D7).
        // Checked via the writes() log, not registers() - REG_ALERT_LIM (0x07) is REG_MASK_EN+1,
        // so the register map's byte-slot model has the ALERT write clobber the MASK write's low
        // byte slot; the literal write log is unaffected by that aliasing.
        sensor.setAlert(Ina226Full.POL, 1.5)
        val maskWrite = connection.writes().last { it.size == 3 && (it[0].toInt() and 0xFF) == Ina226Minimal.REG_MASK_EN }
        val alertWrite = connection.writes().last { it.size == 3 && (it[0].toInt() and 0xFF) == Ina226Minimal.REG_ALERT_LIM }
        assertEquals(0x08.toByte(), maskWrite[1])
        assertEquals(0x00.toByte(), maskWrite[2])
        assertEquals(0x03.toByte(), alertWrite[1])
        assertEquals(0xD7.toByte(), alertWrite[2])

        // alertFlags(): raw Mask/Enable register
        connection.setRegister(Ina226Minimal.REG_MASK_EN, 0x08, 0x03)
        assertEquals(0x0803, sensor.alertFlags())

        // reset(): writes CONFIG=0x8000, then re-writes CAL
        sensor.reset()
        assertEquals(0x80, connection.registers()[Ina226Minimal.REG_CONFIG])
        assertEquals(0x00, connection.registers()[Ina226Minimal.REG_CONFIG + 1])
        assertEquals(0x03, connection.registers()[Ina226Minimal.REG_CAL])
        assertEquals(0x46, connection.registers()[Ina226Minimal.REG_CAL + 1])

        // shutdown(): reads CONFIG, saves mode, writes CONFIG & ~0x07
        connection.setRegister(Ina226Minimal.REG_CONFIG, 0x41, 0x27)
        sensor.shutdown()
        val shutdownWrite = connection.writes().last()
        assertEquals(0x41.toByte(), shutdownWrite[1])
        assertEquals(0x20.toByte(), shutdownWrite[2])

        // wake(): reads CONFIG, writes back with saved mode restored
        connection.setRegister(Ina226Minimal.REG_CONFIG, 0x41, 0x20)
        sensor.wake()
        val wakeWrite = connection.writes().last()
        assertEquals(0x41.toByte(), wakeWrite[1])
        assertEquals(0x27.toByte(), wakeWrite[2])

        // manufacturerId() / dieId()
        connection.setRegister(Ina226Minimal.REG_MFR_ID, 0x54, 0x49)
        assertEquals(0x5449, sensor.manufacturerId())
        connection.setRegister(Ina226Minimal.REG_DIE_ID, 0x22, 0x60)
        assertEquals(0x2260, sensor.dieId())
    }
}
