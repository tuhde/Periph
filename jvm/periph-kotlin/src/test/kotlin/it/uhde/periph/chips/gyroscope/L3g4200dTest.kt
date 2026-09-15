package it.uhde.periph.chips.gyroscope

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class L3g4200dTest {

    @Test
    fun fullApi() {
        val connection = MockConnection()
        connection.setRegister(L3g4200dMinimal.REG_WHO_AM_I, 0xD3)

        val sensor = L3g4200dFull(connection, false)

        assertEquals(L3g4200dFull.CTRL_REG4_DEFAULT.toByte(),
                connection.registers()[L3g4200dMinimal.REG_CTRL_REG4]!!)
        assertEquals(L3g4200dFull.CTRL_REG1_DEFAULT.toByte(),
                connection.registers()[L3g4200dMinimal.REG_CTRL_REG1]!!)

        connection.setRegister(L3g4200dMinimal.REG_OUT_X_L or 0x80,
                0x10, 0x00,
                0x00, 0x00,
                0xF0, 0xFF)
        val xyz = sensor.angularRate()
        val k = (Math.PI / 180.0).toFloat()
        assertEquals(16.0f * 0.00875f * k, xyz.first, 1e-6f)
        assertEquals(0.0f, xyz.second, 1e-6f)
        assertEquals(-16.0f * 0.00875f * k, xyz.third, 1e-6f)

        sensor.configure(L3g4200dFull.ODR_200_HZ, 0, L3g4200dFull.FS_500_DPS)
        assertEquals((L3g4200dFull.CTRL_REG1_DEFAULT or (1 shl 6)).toByte(),
                connection.registers()[L3g4200dMinimal.REG_CTRL_REG1]!!)
        assertEquals((L3g4200dFull.CTRL_REG4_DEFAULT or (1 shl 4)).toByte(),
                connection.registers()[L3g4200dMinimal.REG_CTRL_REG4]!!)
        assertEquals(500, sensor.fullScale)

        connection.setRegister(L3g4200dMinimal.REG_CTRL_REG4,
                L3g4200dFull.CTRL_REG4_DEFAULT or (1 shl 4))
        sensor.setFullScale(L3g4200dFull.FS_2000_DPS)
        assertEquals((L3g4200dFull.CTRL_REG4_DEFAULT or (2 shl 4)).toByte(),
                connection.registers()[L3g4200dMinimal.REG_CTRL_REG4]!!)
        assertEquals(2000, sensor.fullScale)

        connection.setRegister(L3g4200dMinimal.REG_STATUS, 0x08)
        assertEquals(0x08, sensor.status())
        assertTrue(sensor.dataReady())
        connection.setRegister(L3g4200dMinimal.REG_OUT_TEMP, 0x80)
        assertEquals(-128, sensor.temperature())

        connection.setRegister(L3g4200dMinimal.REG_CTRL_REG1, 0x4F)
        sensor.powerDown()
        assertEquals(0x47.toByte(), connection.registers()[L3g4200dMinimal.REG_CTRL_REG1]!!)
        sensor.wakeUp()
        assertEquals(0x4F.toByte(), connection.registers()[L3g4200dMinimal.REG_CTRL_REG1]!!)
        sensor.sleep()
        assertEquals(0x08.toByte(), connection.registers()[L3g4200dMinimal.REG_CTRL_REG1]!!)

        connection.setRegister(L3g4200dMinimal.REG_CTRL_REG1, 0x08)
        sensor.enableAxes(false, true, false)
        assertEquals(0x0A.toByte(), connection.registers()[L3g4200dMinimal.REG_CTRL_REG1]!!)

        connection.setRegister(L3g4200dMinimal.REG_CTRL_REG5, 0x00)
        sensor.enableFifo(L3g4200dFull.FIFO_STREAM, 10)
        assertEquals(0x40.toByte(), connection.registers()[L3g4200dMinimal.REG_CTRL_REG5]!!)
        assertEquals(((2 shl 5) or 10).toByte(), connection.registers()[L3g4200dMinimal.REG_FIFO_CTRL]!!)

        connection.setRegister(L3g4200dMinimal.REG_FIFO_SRC, 0x1A)
        assertEquals(26, sensor.fifoSamples())

        sensor.enableHighpass(2, 5)
        assertEquals(((2 shl 4) or 5).toByte(), connection.registers()[L3g4200dMinimal.REG_CTRL_REG2]!!)
        assertEquals((0x40 or 0x10).toByte(), connection.registers()[L3g4200dMinimal.REG_CTRL_REG5]!!)
        sensor.disableHighpass()
        assertEquals(0x40.toByte(), connection.registers()[L3g4200dMinimal.REG_CTRL_REG5]!!)

        sensor.setInterrupt(xHigh = true, yHigh = true, zHigh = true, latch = true)
        assertEquals(0x6A.toByte(), connection.registers()[L3g4200dMinimal.REG_INT1_CFG]!!)

        sensor.setThreshold('x', 87.5f)
        assertEquals(0x04.toByte(), connection.registers()[L3g4200dMinimal.REG_INT1_THS_XH]!!)
        assertEquals(0xE2.toByte(), connection.registers()[L3g4200dMinimal.REG_INT1_THS_XL]!!)

        sensor.setDuration(4, wait = true)
        assertEquals(0x84.toByte(), connection.registers()[L3g4200dMinimal.REG_INT1_DURATION]!!)

        connection.setRegister(L3g4200dMinimal.REG_CTRL_REG3, 0x00)
        sensor.setDataReadyPin(true)
        assertEquals(0x08.toByte(), connection.registers()[L3g4200dMinimal.REG_CTRL_REG3]!!)
    }
}
