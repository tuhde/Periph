package it.uhde.periph.chips.light

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Apds9960Test {

    @Test
    fun fullApi() {
        val connection = MockConnection()
        connection.setRegister(Apds9960Minimal.REG_ID, 0xAB)

        val sensor = Apds9960Full(connection)

        assertEquals(0xB6, connection.registers()[Apds9960Minimal.REG_ATIME])
        assertEquals(0x01, connection.registers()[Apds9960Minimal.REG_CONTROL])
        assertEquals(0x01, connection.registers()[Apds9960Minimal.REG_CONFIG2])
        assertEquals(0x03, connection.registers()[Apds9960Minimal.REG_ENABLE])

        // A bad ID must reject construction.
        val badConnection = MockConnection()
        badConnection.setRegister(Apds9960Minimal.REG_ID, 0x00)
        assertThrows(java.io.IOException::class.java) { Apds9960Full(badConnection) }

        // Color burst: clear=0x1234, red=0x0102, green=0x0304, blue=0x0506 (LE).
        connection.setRegister(Apds9960Minimal.REG_CDATAL, 0x34, 0x12, 0x02, 0x01, 0x04, 0x03, 0x06, 0x05)
        val rgbc = sensor.color()
        assertArrayEquals(intArrayOf(0x1234, 0x0102, 0x0304, 0x0506), rgbc)
        assertEquals(0x1234, sensor.colorClear())
        assertEquals(0x0102, sensor.colorRed())
        assertEquals(0x0304, sensor.colorGreen())
        assertEquals(0x0506, sensor.colorBlue())

        sensor.enableProximity(true)
        assertEquals(0x07, connection.registers()[Apds9960Minimal.REG_ENABLE])
        sensor.enableProximity(false)
        assertEquals(0x03, connection.registers()[Apds9960Minimal.REG_ENABLE])

        connection.setRegister(Apds9960Minimal.REG_PDATA, 200)
        assertEquals(200, sensor.proximity())

        sensor.enableWait(true)
        assertEquals(0x0B, connection.registers()[Apds9960Minimal.REG_ENABLE])
        sensor.enableWait(false)
        assertEquals(0x03, connection.registers()[Apds9960Minimal.REG_ENABLE])

        sensor.configureWait(100, true)
        assertEquals(100, connection.registers()[Apds9960Minimal.REG_WTIME])
        assertEquals(0x62, connection.registers()[Apds9960Minimal.REG_CONFIG1])
        sensor.configureWait(50, false)
        assertEquals(0x60, connection.registers()[Apds9960Minimal.REG_CONFIG1])

        sensor.configureAls(0xDB, 2)
        assertEquals(0xDB, connection.registers()[Apds9960Minimal.REG_ATIME])
        assertEquals(2, connection.registers()[Apds9960Minimal.REG_CONTROL]!! and 0x03)

        sensor.configureProximityLed(1, 2, 10, 3)
        val ctrl = connection.registers()[Apds9960Minimal.REG_CONTROL]!!
        assertEquals(1, (ctrl shr 6) and 0x03)
        assertEquals(2, (ctrl shr 2) and 0x03)
        assertEquals((3 shl 6) or 10, connection.registers()[Apds9960Minimal.REG_PPULSE])

        sensor.setLedBoost(2)
        assertEquals((2 shl 4) or 0x01, connection.registers()[Apds9960Minimal.REG_CONFIG2])

        sensor.alsThreshold(0x1234, 0x5678)
        assertEquals(0x34, connection.registers()[Apds9960Minimal.REG_AILTL])
        assertEquals(0x12, connection.registers()[Apds9960Minimal.REG_AILTH])
        assertEquals(0x78, connection.registers()[Apds9960Minimal.REG_AIHTL])
        assertEquals(0x56, connection.registers()[Apds9960Minimal.REG_AIHTH])

        sensor.proximityThreshold(10, 200)
        assertEquals(10, connection.registers()[Apds9960Minimal.REG_PILT])
        assertEquals(200, connection.registers()[Apds9960Minimal.REG_PIHT])

        sensor.setPersistence(5, 3)
        assertEquals((5 shl 4) or 3, connection.registers()[Apds9960Minimal.REG_PERS])

        sensor.enableAlsInterrupt(true)
        assertTrue((connection.registers()[Apds9960Minimal.REG_ENABLE]!! and 0x10) != 0)
        sensor.enableProximityInterrupt(true)
        assertTrue((connection.registers()[Apds9960Minimal.REG_ENABLE]!! and 0x20) != 0)

        sensor.clearProximityInterrupt()
        assertArrayEquals(byteArrayOf(Apds9960Minimal.REG_PICLEAR.toByte()), connection.writes().last())
        sensor.clearAlsInterrupt()
        assertArrayEquals(byteArrayOf(Apds9960Minimal.REG_CICLEAR.toByte()), connection.writes().last())
        sensor.clearAllInterrupts()
        assertArrayEquals(byteArrayOf(Apds9960Minimal.REG_AICLEAR.toByte()), connection.writes().last())

        // Sign-magnitude proximity offset encoding: -50 -> 0x80|50=0xB2, 100 -> 0x64.
        sensor.setProximityOffset(-50, 100)
        assertEquals(0xB2, connection.registers()[Apds9960Minimal.REG_POFFSET_UR])
        assertEquals(0x64, connection.registers()[Apds9960Minimal.REG_POFFSET_DL])

        sensor.setProximityMask(u = true, d = false, l = true, r = false)
        assertEquals(0x08 or 0x02, connection.registers()[Apds9960Minimal.REG_CONFIG3])

        sensor.enableGesture(true)
        assertTrue((connection.registers()[Apds9960Minimal.REG_ENABLE]!! and 0x40) != 0)
        assertTrue((connection.registers()[Apds9960Minimal.REG_GCONF4]!! and 0x01) != 0)
        sensor.enableGesture(false)
        assertEquals(0, connection.registers()[Apds9960Minimal.REG_ENABLE]!! and 0x40)
        assertEquals(0, connection.registers()[Apds9960Minimal.REG_GCONF4]!! and 0x01)

        sensor.configureGesture(1, 2, 20, 3, 5, 30, 10)
        assertEquals(30, connection.registers()[Apds9960Minimal.REG_GPENTH])
        assertEquals(10, connection.registers()[Apds9960Minimal.REG_GEXTH])
        assertEquals((1 shl 5) or (2 shl 3) or 5, connection.registers()[Apds9960Minimal.REG_GCONF2])
        assertEquals((3 shl 6) or 20, connection.registers()[Apds9960Minimal.REG_GPULSE])

        connection.setRegister(Apds9960Minimal.REG_GSTATUS, 0x01)
        assertTrue(sensor.gestureAvailable())

        connection.setRegister(Apds9960Minimal.REG_GFLVL, 2)
        connection.setRegister(Apds9960Minimal.REG_GFIFO_U, 10, 20, 30, 40)
        val fifo = sensor.readGestureFifo()
        assertEquals(2, fifo.size)
        assertArrayEquals(intArrayOf(10, 20, 30, 40), fifo[0])

        connection.setRegister(Apds9960Minimal.REG_GFLVL, 0)
        assertEquals(0, sensor.readGestureFifo().size)
        assertEquals(0, sensor.gestureFifoLevel())

        sensor.clearGestureFifo()
        assertTrue((connection.registers()[Apds9960Minimal.REG_GCONF4]!! and 0x04) != 0)

        sensor.enableGestureInterrupt(true)
        assertTrue((connection.registers()[Apds9960Minimal.REG_GCONF4]!! and 0x02) != 0)

        connection.setRegister(Apds9960Minimal.REG_STATUS, 0x93) // CPSAT|PVALID|AVALID
        assertEquals(0x93, sensor.status())
        assertTrue(sensor.isAlsValid())
        assertTrue(sensor.isProximityValid())
        assertTrue(sensor.isAlsSaturated())
        assertFalse(sensor.isProximitySaturated())

        assertEquals(0xAB, sensor.chipId())
    }
}
