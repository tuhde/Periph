package it.uhde.periph.chips.light

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Apds9930Test {

    private fun cw(reg: Int) = Apds9930Minimal.CMD_WRITE or (reg and 0x1F)
    private fun cr(reg: Int) = Apds9930Minimal.CMD_READ  or (reg and 0x1F)

    @Test
    fun fullApi() {
        val connection = MockConnection()
        // APDS-9930 uses a command-register protocol — every bus transaction
        // starts with a command byte (0x80|reg, 0xA0|reg, 0xE0|fn). The mock
        // is addressed by raw byte index, so preload registers at the
        // command-byte form.
        connection.setRegister(cr(Apds9930Minimal.REG_ID), 0x39)

        val sensor = Apds9930Full(connection)

        assertEquals(Apds9930Minimal.ATIME_DEFAULT,
            connection.registers()[cw(Apds9930Minimal.REG_ATIME)]!! and 0xFF)
        assertEquals(Apds9930Minimal.CONTROL_DEFAULT,
            connection.registers()[cw(Apds9930Minimal.REG_CONTROL)]!! and 0xFF)
        assertEquals(Apds9930Minimal.ENABLE_DEFAULT,
            connection.registers()[cw(Apds9930Minimal.REG_ENABLE)]!! and 0xFF)

        // Bad ID rejects construction.
        val badConnection = MockConnection()
        badConnection.setRegister(cr(Apds9930Minimal.REG_ID), 0xAB)
        assertThrows(java.io.IOException::class.java) { Apds9930Full(badConnection) }

        // Lux: Ch0=0x1000 (BE), Ch1=0x0000 → lux > 0
        connection.setRegister(cr(Apds9930Minimal.REG_CH0DATAL), 0x10, 0x00)
        connection.setRegister(cr(Apds9930Minimal.REG_CH1DATAL), 0x00, 0x00)
        connection.setRegister(cr(Apds9930Minimal.REG_CONTROL), Apds9930Minimal.CONTROL_DEFAULT)
        connection.setRegister(cr(Apds9930Minimal.REG_CONFIG), 0x00)
        connection.setRegister(cr(Apds9930Minimal.REG_ATIME), Apds9930Minimal.ATIME_DEFAULT)
        assertTrue(sensor.lux() > 0.0f)

        // Dark → lux = 0
        connection.setRegister(cr(Apds9930Minimal.REG_CH0DATAL), 0x00, 0x00)
        connection.setRegister(cr(Apds9930Minimal.REG_CH1DATAL), 0x00, 0x00)
        assertEquals(0.0f, sensor.lux())

        // Proximity: 0x1234 (BE)
        connection.setRegister(cr(Apds9930Minimal.REG_PDATAL), 0x12, 0x34)
        assertEquals(0x1234, sensor.proximity())

        // configureAls(0xF6, 2, false)
        sensor.configureAls(0xF6, 2, false)
        assertEquals(0xF6, connection.registers()[cw(Apds9930Minimal.REG_ATIME)]!! and 0xFF)
        assertEquals(2, connection.registers()[cw(Apds9930Minimal.REG_CONTROL)]!! and 0x03)

        // configureProximity(8, 1, 2, false, 0xFF)
        sensor.configureProximity(8, 1, 2, false, 0xFF)
        assertEquals(8, connection.registers()[cw(Apds9930Minimal.REG_PPULSE)]!! and 0xFF)
        assertEquals(0xFF, connection.registers()[cw(Apds9930Minimal.REG_PTIME)]!! and 0xFF)

        // configureWait(0x80, true)
        sensor.configureWait(0x80, true)
        assertEquals(0x80, connection.registers()[cw(Apds9930Minimal.REG_WTIME)]!! and 0xFF)
        assertEquals(0x02, connection.registers()[cw(Apds9930Minimal.REG_CONFIG)]!! and 0x02)

        sensor.disableWait()
        assertEquals(0, connection.registers()[cw(Apds9930Minimal.REG_ENABLE)]!! and 0x08)

        // status: STATUS=0x01 → AVALID
        connection.setRegister(cr(Apds9930Minimal.REG_STATUS), 0x01)
        val st = sensor.status()
        assertTrue(st.avalid)
        assertFalse(st.pvalid)

        sensor.setAlsThresholds(100, 60000, 1)
        assertEquals(0x10, connection.registers()[cw(Apds9930Minimal.REG_ENABLE)]!! and 0x10)

        sensor.setProximityThresholds(10, 200, 1)
        assertEquals(0x20, connection.registers()[cw(Apds9930Minimal.REG_ENABLE)]!! and 0x20)

        sensor.setProximityOffset(-50)
        assertEquals(0x32, connection.registers()[cw(Apds9930Minimal.REG_POFFSET)]!! and 0xFF)

        sensor.sleepAfterInterrupt(true)
        assertEquals(0x40, connection.registers()[cw(Apds9930Minimal.REG_ENABLE)]!! and 0x40)
        sensor.sleepAfterInterrupt(false)
        assertEquals(0, connection.registers()[cw(Apds9930Minimal.REG_ENABLE)]!! and 0x40)

        // clearInterrupt
        sensor.clearInterrupt(0)
        val last0 = connection.writes().last()
        assertEquals(1, last0.size)
        assertEquals(0xE7.toByte(), last0[0])
        sensor.clearInterrupt(1)
        val last1 = connection.writes().last()
        assertEquals(0xE6.toByte(), last1[0])
        sensor.clearInterrupt(2)
        val last2 = connection.writes().last()
        assertEquals(0xE5.toByte(), last2[0])

        assertEquals(0x39, sensor.chipId())
    }
}