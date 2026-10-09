package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertThrows
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class Bma150Test {

    private fun newConnection(): MockConnection {
        val c = MockConnection()
        c.setRegister(Bma150Minimal.REG_CHIP_ID, 0x02)
        c.setRegister(Bma150Minimal.REG_RANGE_BW, 0x00)
        c.setRegister(Bma150Minimal.REG_ACC_X_LSB, 0x00, 0x80)
        c.setRegister(Bma150Minimal.REG_ACC_Y_LSB, 0x00, 0x00)
        c.setRegister(Bma150Minimal.REG_ACC_Z_LSB, 0x00, 0x40)
        return c
    }

    private fun lastWriteTo(connection: MockConnection, reg: Int): Int {
        val writes = connection.writes()
        for (i in writes.indices.reversed()) {
            val w = writes[i]
            if (w.size == 2 && (w[0].toInt() and 0xFF) == reg) return w[1].toInt() and 0xFF
        }
        return -1
    }

    @Test
    fun constructionAndRead() {
        val connection = newConnection()
        val accel = Bma150Minimal(connection)
        assertEquals(0x02, lastWriteTo(connection, Bma150Minimal.REG_RANGE_BW))

        val xyz = accel.read()
        assertEquals(-2.0, xyz[0], 1e-9)
        assertEquals(0.0, xyz[1], 1e-9)
        assertEquals(1.0, xyz[2], 1e-9)
    }

    @Test
    fun constructionBadChipIdThrows() {
        val connection = MockConnection()
        connection.setRegister(Bma150Minimal.REG_CHIP_ID, 0xFF)
        assertThrows(IOException::class.java) { Bma150Minimal(connection) }
    }

    @Test
    fun rangeAndBandwidth() {
        val connection = newConnection()
        val full = Bma150Full(connection)
        full.setRange(4)
        assertEquals(0x0A, lastWriteTo(connection, Bma150Minimal.REG_RANGE_BW))
        connection.setRegister(Bma150Minimal.REG_RANGE_BW, 0x0A)
        full.setBandwidth(190)
        assertEquals(0x0B, lastWriteTo(connection, Bma150Minimal.REG_RANGE_BW))
    }

    @Test
    fun readRaw() {
        val connection = newConnection()
        val full = Bma150Full(connection)
        val raw = full.readRaw()
        assertEquals(-512, raw[0])
        assertEquals(0, raw[1])
        assertEquals(256, raw[2])
    }

    @Test
    fun readTemperature() {
        val connection = newConnection()
        val full = Bma150Full(connection)
        connection.setRegister(Bma150Minimal.REG_TEMP, 0x40)
        assertEquals(2.0, full.readTemperature(), 1e-9)
    }

    @Test
    fun lowGAndHighG() {
        val connection = newConnection()
        val full = Bma150Full(connection)
        full.setLowG(0.4, 40, 0.0, 0)
        assertEquals(51, lastWriteTo(connection, Bma150Minimal.REG_LG_THRES))
        assertEquals(40, lastWriteTo(connection, Bma150Minimal.REG_LG_DUR))
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_INT_CTRL) and 0x01) != 0)
        full.setHighG(4.0, 2, 0.0, 0)
        assertEquals(255, lastWriteTo(connection, Bma150Minimal.REG_HG_THRES))
        assertEquals(2, lastWriteTo(connection, Bma150Minimal.REG_HG_DUR))
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_INT_CTRL) and 0x02) != 0)
    }

    @Test
    fun anyMotion() {
        val connection = newConnection()
        val full = Bma150Full(connection)
        full.setAnyMotion(0.5, 3)
        assertEquals(32, lastWriteTo(connection, Bma150Minimal.REG_ANY_MOTION_THRES))
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_HYST_DUR) and 0xC0) == 0x40)
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_CONFIG) and 0x40) != 0)
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_INT_CTRL) and 0x40) != 0)
    }

    @Test
    fun latchAndClear() {
        val connection = newConnection()
        val full = Bma150Full(connection)
        full.setLatch(true)
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_CONFIG) and 0x10) != 0)
        full.clearInterrupt()
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_CTRL) and 0x40) != 0)
    }

    @Test
    fun sleepWakeAndWakeUp() {
        val connection = newConnection()
        val full = Bma150Full(connection)
        full.sleep()
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_CTRL) and 0x01) != 0)
        full.wake()
        assertEquals(0, lastWriteTo(connection, Bma150Minimal.REG_CTRL) and 0x01)
        connection.setRegister(Bma150Minimal.REG_CONFIG, 0x00)
        full.setWakeUp(true, 80)
        val cfg = lastWriteTo(connection, Bma150Minimal.REG_CONFIG)
        assertTrue((cfg and 0x03) == 0x03 && (cfg and 0x06) == 0x02)
    }

    @Test
    fun softReset() {
        val connection = newConnection()
        val full = Bma150Full(connection)
        full.softReset()
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_CTRL) and 0x02) != 0)
    }

    @Test
    fun selfTest() {
        val connection = newConnection()
        val full = Bma150Full(connection)
        connection.setRegister(Bma150Minimal.REG_STATUS, 0x80)
        assertTrue(full.selfTest())
    }

    @Test
    fun versionAndCustomer() {
        val connection = newConnection()
        val full = Bma150Full(connection)
        connection.setRegister(Bma150Minimal.REG_VERSION, 0xAB)
        val v = full.readVersion()
        assertEquals(0x0A, v[0])
        assertEquals(0x0B, v[1])
        connection.setRegister(Bma150Minimal.REG_CUSTOMER_1, 0xA5)
        assertEquals(0xA5, full.readCustomer(0))
        full.writeCustomer(1, 0x5A)
        assertEquals(0x5A, lastWriteTo(connection, Bma150Minimal.REG_CUSTOMER_2))
    }

    @Test
    fun setShadow() {
        val connection = newConnection()
        val full = Bma150Full(connection)
        full.setShadow(true)
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_CONFIG) and 0x08) != 0)
        full.setShadow(false)
        assertEquals(0, lastWriteTo(connection, Bma150Minimal.REG_CONFIG) and 0x08)
    }

    @Test
    fun debounceCounterBits() {
        val connection = newConnection()
        val full = Bma150Full(connection)
        // counter_LG is INT_CTRL bits 3:2, counter_HG is bits 5:4.
        connection.setRegister(Bma150Minimal.REG_INT_CTRL, 0x00)
        full.setLowG(0.4, 40, 0.0, 2)
        assertEquals(0x09, lastWriteTo(connection, Bma150Minimal.REG_INT_CTRL))
        connection.setRegister(Bma150Minimal.REG_INT_CTRL, 0x00)
        full.setHighG(2.0, 2, 0.0, 2)
        assertEquals(0x22, lastWriteTo(connection, Bma150Minimal.REG_INT_CTRL))
    }
}
