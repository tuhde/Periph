package it.uhde.periph.chips.magnetometer

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class As5600Test {

    @Test
    fun fullApi() {
        val connection = MockConnection()
        // STATUS: MD=1 (magnet detected), MH=0, ML=0.
        connection.setRegister(As5600Minimal.REG_STATUS, 0x08)

        val sensor = As5600Full(connection)

        assertTrue(sensor.isMagnetDetected())
        assertFalse(sensor.isMagnetTooStrong())
        assertFalse(sensor.isMagnetTooWeak())

        // ANGLE burst (0x0E-0x0F): H=0x01, L=0x23 -> raw = 0x0123 = 291.
        connection.setRegister(As5600Minimal.REG_ANGLE_H, 0x01, 0x23)
        assertEquals(291, sensor.angleRaw())
        assertEquals(291 * 360.0 / 4096.0, sensor.angle(), 1e-9)

        // RAW_ANGLE burst (0x0C-0x0D): H=0x02, L=0x00 -> raw = 512 -> 45.0 degrees.
        connection.setRegister(As5600Minimal.REG_RAW_ANGLE_H, 0x02, 0x00)
        assertEquals(512, sensor.rawAngle())
        assertEquals(45.0, sensor.rawAngleDegrees(), 1e-9)

        connection.setRegister(As5600Minimal.REG_AGC, 128)
        assertEquals(128, sensor.agc())

        // MAGNITUDE burst (0x1B-0x1C): H=0x00, L=0x64 -> raw = 100.
        connection.setRegister(As5600Minimal.REG_MAGNITUDE_H, 0x00, 0x64)
        assertEquals(100, sensor.magnitude())

        // STATUS: MD=1, MH=1 (magnet too strong).
        connection.setRegister(As5600Minimal.REG_STATUS, 0x28)
        assertTrue(sensor.isMagnetTooStrong())
        assertEquals(0x28, sensor.statusByte())

        // configure() must preserve CONF_H[7:6] reserved bits (preloaded as 0xC5).
        connection.setRegister(As5600Minimal.REG_CONF_H, 0xC5, 0x00)
        sensor.configure(pm = 1, hyst = 2, outs = 1, pwmf = 3, sf = 2, fth = 5, wd = true)
        assertEquals(0xF6, connection.registers()[As5600Minimal.REG_CONF_H])
        assertEquals(0xD9, connection.registers()[As5600Minimal.REG_CONF_L])

        sensor.setZeroPosition(1000)
        assertEquals(1000, sensor.zeroPosition())

        sensor.setMaxPosition(2000)
        assertEquals(2000, sensor.maxPosition())

        sensor.setMaxAngle(2048)
        assertEquals(2048, sensor.maxAngle())

        connection.setRegister(As5600Minimal.REG_ZMCO, 0x02)
        assertEquals(2, sensor.burnCount())

        // burnAngle(): MD=1 (STATUS=0x28), ZMCO=2 < 3 -> succeeds, writes BURN=0x80 first.
        sensor.burnAngle()
        val burnAngleWrites = connection.writes()
            .count { it.size == 2 && (it[0].toInt() and 0xFF) == As5600Minimal.REG_BURN && (it[1].toInt() and 0xFF) == 0x80 }
        assertEquals(1, burnAngleWrites)

        // burnSetting(): requires ZMCO=0.
        connection.setRegister(As5600Minimal.REG_ZMCO, 0x00)
        sensor.burnSetting()
        val burnSettingWrites = connection.writes()
            .count { it.size == 2 && (it[0].toInt() and 0xFF) == As5600Minimal.REG_BURN && (it[1].toInt() and 0xFF) == 0x40 }
        assertEquals(1, burnSettingWrites)

        // burnAngle() must throw when magnet not detected.
        connection.setRegister(As5600Minimal.REG_STATUS, 0x00)
        assertThrows(Exception::class.java) { sensor.burnAngle() }

        // burnAngle() must throw when ZMCO limit (3) reached.
        connection.setRegister(As5600Minimal.REG_STATUS, 0x08)
        connection.setRegister(As5600Minimal.REG_ZMCO, 0x03)
        assertThrows(Exception::class.java) { sensor.burnAngle() }

        // burnSetting() must throw when ZMCO != 0.
        connection.setRegister(As5600Minimal.REG_ZMCO, 0x01)
        assertThrows(Exception::class.java) { sensor.burnSetting() }

        // Construction must throw when no magnet is detected.
        val noMagnetConnection = MockConnection()
        noMagnetConnection.setRegister(As5600Minimal.REG_STATUS, 0x00)
        assertThrows(Exception::class.java) { As5600Full(noMagnetConnection) }
    }
}
