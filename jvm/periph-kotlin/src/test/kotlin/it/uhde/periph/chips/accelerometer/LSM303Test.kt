package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class LSM303Test {

    /**
     * Preload the accelerometer output registers with 12-bit counts.
     *
     * The high-resolution mode writes the 12-bit value left-justified into the
     * 16-bit register pair.
     */
    private fun setAccelOutput(conn: MockConnection, x: Int, y: Int, z: Int) {
        conn.setRegister(
            LSM303Minimal.REG_OUT_X_L_A or LSM303Minimal.ACCEL_AUTO_INCREMENT,
            (x shl 4) and 0xFF, ((x shl 4) shr 8) and 0xFF,
            (y shl 4) and 0xFF, ((y shl 4) shr 8) and 0xFF,
            (z shl 4) and 0xFF, ((z shl 4) shr 8) and 0xFF
        )
    }

    /**
     * Preload the magnetometer output registers with 12-bit counts.
     *
     * The magnetometer writes the 12-bit value right-justified into the 16-bit
     * register pair and sends X, Z, Y on the wire.
     */
    private fun setMagOutput(conn: MockConnection, x: Int, y: Int, z: Int) {
        conn.setRegister(
            LSM303Minimal.REG_OUT_X_H_M,
            (x shr 8) and 0xFF, x and 0xFF,
            (z shr 8) and 0xFF, z and 0xFF,
            (y shr 8) and 0xFF, y and 0xFF
        )
    }

    private fun newAccelConnection() = MockConnection()

    private fun newMagConnection(): MockConnection {
        val c = MockConnection()
        c.setRegister(LSM303Minimal.REG_IRA_M, 0x48, 0x34, 0x33)
        return c
    }

    /**
     * Return the value of the last write to [reg].
     *
     * The mock accumulates every write since construction, so "the register now
     * holds X" has to look at the last write to that register.
     */
    private fun lastWriteTo(connection: MockConnection, reg: Int): Int {
        val writes = connection.writes()
        for (i in writes.indices.reversed()) {
            val w = writes[i]
            if (w.size == 2 && (w[0].toInt() and 0xFF) == reg) return w[1].toInt() and 0xFF
        }
        return -1
    }

    @Test
    fun constructionWritesDefaults() {
        val accel = newAccelConnection()
        val mag = newMagConnection()
        LSM303Minimal(accel, mag)
        assertEquals(0x88, lastWriteTo(accel, LSM303Minimal.REG_CTRL4_A))
        assertEquals(0x57, lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A))
        assertEquals(0x10, lastWriteTo(mag, LSM303Minimal.REG_CRA_M))
        assertEquals(0x20, lastWriteTo(mag, LSM303Minimal.REG_CRB_M))
        assertEquals(0x00, lastWriteTo(mag, LSM303Minimal.REG_MR_M))
    }

    @Test
    fun constructionBadIdentityThrows() {
        val accel = newAccelConnection()
        val mag = MockConnection()
        mag.setRegister(LSM303Minimal.REG_IRA_M, 0x00, 0x00, 0x00)
        assertThrows(IOException::class.java) { LSM303Minimal(accel, mag) }
    }

    @Test
    fun readAcceleration() {
        val accel = newAccelConnection()
        val mag = newMagConnection()
        val compass = LSM303Minimal(accel, mag)
        setAccelOutput(accel, 1000, -2000, 2000)
        val xyz = compass.readAcceleration()
        assertEquals(1.0, xyz[0], 1e-9)
        assertEquals(-2.0, xyz[1], 1e-9)
        assertEquals(2.0, xyz[2], 1e-9)
    }

    @Test
    fun readMagnetic() {
        val accel = newAccelConnection()
        val mag = newMagConnection()
        val compass = LSM303Minimal(accel, mag)
        setMagOutput(mag, 1000, 3000, 2000)
        val xyz = compass.readMagnetic()
        assertEquals(1000.0 / 1100.0 * 100, xyz[0], 1e-6)
        assertEquals(3000.0 / 1100.0 * 100, xyz[1], 1e-6)
        assertEquals(2000.0 / 980.0 * 100, xyz[2], 1e-6)
    }

    @Test
    fun setRangeAndDataRate() {
        val accel = newAccelConnection()
        val mag = newMagConnection()
        val compass = LSM303Full(accel, mag)

        accel.setRegister(LSM303Minimal.REG_CTRL4_A, 0x88)
        compass.setRange(8)
        assertEquals(0xA8, lastWriteTo(accel, LSM303Minimal.REG_CTRL4_A))
        assertThrows(IOException::class.java) { compass.setRange(3) }

        accel.setRegister(LSM303Minimal.REG_CTRL1_A, 0x57)
        compass.setDataRate(400)
        assertEquals(0x77, lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A))
        assertThrows(IOException::class.java) { compass.setDataRate(123) }
    }

    @Test
    fun setModeAndAxes() {
        val accel = newAccelConnection()
        val mag = newMagConnection()
        val compass = LSM303Full(accel, mag)

        accel.setRegister(LSM303Minimal.REG_CTRL1_A, 0x57)
        accel.setRegister(LSM303Minimal.REG_CTRL4_A, 0x88)
        compass.setMode(LSM303Minimal.MODE_LOW_POWER)
        assertEquals(0x5F, lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A))
        assertEquals(0x80, lastWriteTo(accel, LSM303Minimal.REG_CTRL4_A))
        assertThrows(IOException::class.java) { compass.setMode(9) }

        accel.setRegister(LSM303Minimal.REG_CTRL1_A, 0x57)
        compass.setAxes(true, false, true)
        assertEquals(0x55, lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A))
    }

    @Test
    fun powerDownAndStatus() {
        val accel = newAccelConnection()
        val mag = newMagConnection()
        val compass = LSM303Full(accel, mag)

        accel.setRegister(LSM303Minimal.REG_CTRL1_A, 0x57)
        compass.powerDown()
        assertEquals(0x07, lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A))

        accel.setRegister(LSM303Minimal.REG_STATUS_A, 0x08)
        assertTrue(compass.newDataAvailable())
        assertEquals(0x08, compass.readStatus())
    }

    @Test
    fun highPass() {
        val accel = newAccelConnection()
        val mag = newMagConnection()
        val compass = LSM303Full(accel, mag)

        // mode=2 (0x80), cutoff=1 (0x10), FDS (0x08), HPCLICK (0x04), HPIS1 (0x01).
        compass.setHighPass(2, 1, true, true, false, true)
        assertEquals(0x9D, lastWriteTo(accel, LSM303Minimal.REG_CTRL2_A))
        assertThrows(IOException::class.java) { compass.setHighPass(4, 0, false, false, false, false) }

        accel.setRegister(LSM303Minimal.REG_REFERENCE_A, 0x42)
        assertEquals(0x42, compass.resetHighPass())

        compass.setReference(0x37)
        assertEquals(0x37, lastWriteTo(accel, LSM303Minimal.REG_REFERENCE_A))
    }

    @Test
    fun fifo() {
        val accel = newAccelConnection()
        val mag = newMagConnection()
        val compass = LSM303Full(accel, mag)

        accel.setRegister(LSM303Minimal.REG_CTRL5_A, 0x00)
        compass.setFifo(LSM303Full.FIFO_STREAM, 16, false)
        assertEquals(0x90, lastWriteTo(accel, LSM303Minimal.REG_FIFO_CTRL_A))
        assertEquals(0x40, lastWriteTo(accel, LSM303Minimal.REG_CTRL5_A))
        assertThrows(IOException::class.java) {
            compass.setFifo(LSM303Full.FIFO_STREAM, 99, false)
        }

        accel.setRegister(LSM303Minimal.REG_FIFO_SRC_A, 0x85)
        assertEquals(5, compass.fifoLevel())

        accel.setRegister(LSM303Minimal.REG_FIFO_SRC_A, 0xE7)
        val fs = compass.readFifoStatus()
        assertEquals(1, fs[0])
        assertEquals(1, fs[1])
        assertEquals(1, fs[2])
        assertEquals(7, fs[3])

        setAccelOutput(accel, 1000, -2000, 2000)
        val samples = compass.readFifo(2)
        assertEquals(2, samples.size)
        assertEquals(1.0, samples[0][0], 1e-9)
    }

    @Test
    fun motionInterrupt() {
        val accel = newAccelConnection()
        val mag = newMagConnection()
        val compass = LSM303Full(accel, mag)

        // The threshold code is relative to the current range: 0.5 g * 128 / 2.
        compass.setMotionInterrupt(1, 0x02, 0.5, 10.0, false)
        assertEquals(0x02, lastWriteTo(accel, LSM303Minimal.REG_INT1_CFG_A))
        assertEquals(0x20, lastWriteTo(accel, LSM303Minimal.REG_INT1_THS_A))
        assertThrows(IOException::class.java) {
            compass.setMotionInterrupt(3, 0x02, 0.5, 10.0, false)
        }
        assertThrows(IOException::class.java) {
            compass.setMotionInterrupt(1, 0x40, 0.5, 10.0, false)
        }

        compass.setFreeFall(1, 0.35, 30.0)
        assertEquals(0x95, lastWriteTo(accel, LSM303Minimal.REG_INT1_CFG_A))

        accel.setRegister(LSM303Minimal.REG_CTRL5_A, 0x00)
        compass.setLatch(1, true)
        assertEquals(0x08, lastWriteTo(accel, LSM303Minimal.REG_CTRL5_A))

        accel.setRegister(LSM303Minimal.REG_INT1_SOURCE_A, 0x41)
        assertEquals(0x41, compass.readInterruptSource(1))
    }

    @Test
    fun click() {
        val accel = newAccelConnection()
        val mag = newMagConnection()
        val compass = LSM303Full(accel, mag)

        // double & CLICK_AXIS_Z -> 0x20; the threshold is 0.8 g * 128 / 2 = 51.
        compass.setClick(0, 0x04, 0.8, 20.0, 40.0, 200.0)
        assertEquals(0x20, lastWriteTo(accel, LSM303Minimal.REG_CLICK_CFG_A))
        assertEquals(51, lastWriteTo(accel, LSM303Minimal.REG_CLICK_THS_A))
        assertThrows(IOException::class.java) { compass.setClick(0x08, 0, 0.5, 10.0, 20.0, 50.0) }

        accel.setRegister(LSM303Minimal.REG_CLICK_SRC_A, 0x42)
        assertEquals(0x42, compass.readClickSource())
    }

    @Test
    fun magnetometer() {
        val accel = newAccelConnection()
        val mag = newMagConnection()
        val compass = LSM303Full(accel, mag)

        mag.setRegister(LSM303Minimal.REG_CRB_M, 0x20)
        compass.setMagRange(400)
        assertEquals(0x80, lastWriteTo(mag, LSM303Minimal.REG_CRB_M))
        assertThrows(IOException::class.java) { compass.setMagRange(123) }

        mag.setRegister(LSM303Minimal.REG_CRA_M, 0x10)
        compass.setMagDataRate(30.0)
        // 30 Hz is the sixth entry of [0.75, 1.5, 3, 7.5, 15, 30, 75, 220].
        assertEquals(0x14, lastWriteTo(mag, LSM303Minimal.REG_CRA_M))
        assertThrows(IOException::class.java) { compass.setMagDataRate(12.0) }

        mag.setRegister(LSM303Minimal.REG_MR_M, 0x00)
        compass.setMagMode(LSM303Full.MAG_MODE_SLEEP)
        assertEquals(0x02, lastWriteTo(mag, LSM303Minimal.REG_MR_M))

        mag.setRegister(LSM303Minimal.REG_SR_M, 0x03)
        val ms = compass.readMagStatus()
        assertTrue(ms[0])
        assertTrue(ms[1])

        val identity = compass.readMagIdentity()
        assertEquals(0x48, identity[0].toInt() and 0xFF)
        assertEquals(0x34, identity[1].toInt() and 0xFF)
        assertEquals(0x33, identity[2].toInt() and 0xFF)

        mag.setRegister(LSM303Minimal.REG_CRA_M, 0x10)
        compass.enableTemperature(true)
        assertEquals(0x90, lastWriteTo(mag, LSM303Minimal.REG_CRA_M))
    }

    @Test
    fun heading() {
        val accel = newAccelConnection()
        val mag = newMagConnection()
        val compass = LSM303Full(accel, mag)

        // X = 1100 counts (1 gauss), Y = 0 -> heading 0 deg (north).
        setMagOutput(mag, 1100, 0, 0)
        assertEquals(0.0, compass.heading(false), 1e-3)

        // X = 0, Y = 1100 counts -> heading 90 deg (east).
        setMagOutput(mag, 0, 1100, 0)
        assertEquals(90.0, compass.heading(false), 1e-3)

        // X = 0, Y = -1100 counts -> heading 270 deg (west).
        setMagOutput(mag, 0, -1100, 0)
        assertEquals(270.0, compass.heading(false), 1e-3)
    }

    @Test
    fun interruptRouting() {
        val accel = newAccelConnection()
        val mag = newMagConnection()
        val compass = LSM303Full(accel, mag)

        accel.setRegister(LSM303Minimal.REG_CTRL3_A, 0x00)
        compass.enableInterrupt(LSM303Full.SOURCE_CLICK, 1)
        assertEquals(0x80, lastWriteTo(accel, LSM303Minimal.REG_CTRL3_A))

        accel.setRegister(LSM303Minimal.REG_CTRL3_A, 0x80)
        compass.disableInterrupt(LSM303Full.SOURCE_CLICK, 1)
        assertEquals(0x00, lastWriteTo(accel, LSM303Minimal.REG_CTRL3_A))

        assertThrows(IOException::class.java) {
            compass.enableInterrupt(LSM303Full.SOURCE_MAG_DATA_READY, 1)
        }
        assertThrows(IOException::class.java) {
            compass.enableInterrupt(LSM303Full.SOURCE_CLICK, 3)
        }

        accel.setRegister(LSM303Minimal.REG_CTRL6_A, 0x00)
        compass.setInterruptPolarity(true)
        assertEquals(0x02, lastWriteTo(accel, LSM303Minimal.REG_CTRL6_A))
    }

    @Test
    fun pollInterrupt() {
        val accel = newAccelConnection()
        val mag = newMagConnection()
        val compass = LSM303Full(accel, mag)

        accel.setRegister(LSM303Minimal.REG_STATUS_A, 0x08)
        accel.setRegister(LSM303Minimal.REG_FIFO_SRC_A, 0x80)
        mag.setRegister(LSM303Minimal.REG_SR_M, 0x00)
        val status = compass.pollInterrupt()
        assertTrue((status and LSM303Full.SOURCE_DATA_READY) != 0)
        assertTrue((status and LSM303Full.SOURCE_WATERMARK) != 0)
        assertFalse((status and LSM303Full.SOURCE_OVERRUN) != 0)
    }

    @Test
    fun rebootSetsBootBit() {
        val accel = newAccelConnection()
        val mag = newMagConnection()
        val compass = LSM303Full(accel, mag)

        accel.setRegister(LSM303Minimal.REG_CTRL5_A, 0x00)
        compass.reboot()
        // The reboot re-runs init(), which rewrites CTRL5_A afterwards, so look
        // for a write with the BOOT bit set rather than at the last write.
        var bootWritten = false
        for (w in accel.writes()) {
            if (w.size == 2 && (w[0].toInt() and 0xFF) == LSM303Minimal.REG_CTRL5_A &&
                (w[1].toInt() and 0x80) != 0
            ) {
                bootWritten = true
            }
        }
        assertTrue(bootWritten)
        assertEquals(0x57, lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A))
    }
}
