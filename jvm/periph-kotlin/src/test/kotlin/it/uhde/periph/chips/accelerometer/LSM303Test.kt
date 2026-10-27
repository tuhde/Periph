package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class LSM303Test {

    private fun newMag(): MockConnection {
        val c = MockConnection()
        c.setRegister(LSM303Minimal.REG_IRA_M, 0x48, 0x34, 0x33)
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
    fun initializationWritesDefaultRegisters() {
        val accel = MockConnection()
        val mag = newMag()
        LSM303Minimal(accel, mag)
        // Init writes CTRL1_A=0x57 (100 Hz, all axes), CTRL4_A=0x88 (BDU+HR, ±2 g),
        // CRA=0x10 (15 Hz), CRB=0x20 (±1.3 gauss), MR=0x00 (continuous).
        assertEquals(0x57, lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A))
        assertEquals(0x88, lastWriteTo(accel, LSM303Minimal.REG_CTRL4_A))
        assertEquals(0x10, lastWriteTo(mag, LSM303Minimal.REG_CRA_M))
        assertEquals(0x20, lastWriteTo(mag, LSM303Minimal.REG_CRB_M))
        assertEquals(0x00, lastWriteTo(mag, LSM303Minimal.REG_MR_M))
    }

    @Test
    fun constructionBadIdentityThrows() {
        val accel = MockConnection()
        val mag = MockConnection()
        mag.setRegister(LSM303Minimal.REG_IRA_M, 0xFF, 0xFF, 0xFF)
        assertThrows(IOException::class.java) {
            LSM303Minimal(accel, mag)
        }
    }

    @Test
    fun accelConversion() {
        val accel = MockConnection()
        val full = LSM303Full(accel, newMag())
        // raw_x = 0x1000 -> >>4 = 256 counts -> 0.256 g at ±2 g high-resolution.
        accel.setRegister(LSM303Minimal.REG_OUT_X_L_A or 0x80, 0x00, 0x10, 0x00, 0x00, 0x00, 0xF0)
        val xyz = full.readAcceleration()
        assertEquals(0.256, xyz[0], 1e-9)
        assertEquals(0.0, xyz[1], 1e-9)
        assertEquals(-0.256, xyz[2], 1e-9)
        assertArrayEquals(intArrayOf(256, 0, -256), full.readAccelerationRaw())
    }

    @Test
    fun magReorderAndGains() {
        val accel = MockConnection()
        val mag = newMag()
        val full = LSM303Full(accel, mag)
        // Wire order is X, Z, Y, high byte first. X=1100, Z=-980, Y=550 counts.
        mag.setRegister(LSM303Minimal.REG_OUT_X_H_M, 0x04, 0x4C, 0xFC, 0x2C, 0x02, 0x26)
        val xyz = full.readMagnetic()
        assertEquals(100.0, xyz[0], 1e-6)      // 1100 / 1100 * 100
        assertEquals(50.0, xyz[1], 1e-6)       // 550 / 1100 * 100 (reordered)
        assertEquals(-100.0, xyz[2], 1e-6)     // -980 / 980 * 100 (Z gain differs)
        assertArrayEquals(intArrayOf(1100, 550, -980), full.readMagneticRaw())
    }

    @Test
    fun setRange16() {
        val accel = MockConnection()
        val full = LSM303Full(accel, newMag())
        full.setRange(16)
        // (0x88 & 0xCF) | (3 << 4) = 0xB8
        assertEquals(0xB8, lastWriteTo(accel, LSM303Minimal.REG_CTRL4_A))
        assertThrows(IllegalArgumentException::class.java) { full.setRange(5) }
    }

    @Test
    fun setDataRateAndModeValidation() {
        val accel = MockConnection()
        val full = LSM303Full(accel, newMag())
        full.setDataRate(50)
        // (0x57 & 0x0F) | (0x4 << 4) = 0x47
        assertEquals(0x47, lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A))
        assertThrows(IllegalArgumentException::class.java) { full.setDataRate(42) }
        assertThrows(IllegalArgumentException::class.java) { full.setDataRate(1620) }  // high-resolution mode
        assertThrows(IllegalArgumentException::class.java) { full.setDataRate(5376) }  // high-resolution mode
    }

    @Test
    fun setLowPowerRejects1344() {
        val accel = MockConnection()
        val full = LSM303Full(accel, newMag())
        full.setMode(0)
        assertThrows(IllegalArgumentException::class.java) { full.setDataRate(1344) }
    }

    @Test
    fun setModeLpenHrBits() {
        val accel = MockConnection()
        val full = LSM303Full(accel, newMag())
        full.setMode(0)
        // LPen=1 in CTRL1: 0x57 -> 0x5F; HR=0 in CTRL4: 0x88 -> 0x80
        assertEquals(0x5F, lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A))
        assertEquals(0x80, lastWriteTo(accel, LSM303Minimal.REG_CTRL4_A))
        full.setMode(2)
        // back to high-resolution: 0x57 / 0x88
        assertEquals(0x57, lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A))
        assertEquals(0x88, lastWriteTo(accel, LSM303Minimal.REG_CTRL4_A))
        assertThrows(IllegalArgumentException::class.java) { full.setMode(3) }
    }

    @Test
    fun fifoCtrlEncoding() {
        val accel = MockConnection()
        val full = LSM303Full(accel, newMag())
        full.setFifo(2, 16, false)
        // (2 << 6) | 16 = 0x90; FIFO_EN -> CTRL5 |= 0x40
        assertEquals(0x90, lastWriteTo(accel, LSM303Minimal.REG_FIFO_CTRL_A))
        assertEquals(0x40, lastWriteTo(accel, LSM303Minimal.REG_CTRL5_A))
        full.setFifo(0, 0, false)
        // bypass: 0x00; CTRL5 &= ~0x40 -> 0x00
        assertEquals(0x00, lastWriteTo(accel, LSM303Minimal.REG_FIFO_CTRL_A))
        assertEquals(0x00, lastWriteTo(accel, LSM303Minimal.REG_CTRL5_A))
        assertThrows(IllegalArgumentException::class.java) { full.setFifo(4, 0, false) }
        assertThrows(IllegalArgumentException::class.java) { full.setFifo(1, 32, false) }
    }

    @Test
    fun freeFallEncoding() {
        val accel = MockConnection()
        val full = LSM303Full(accel, newMag())
        // ±2 g, 100 Hz: THS = round(0.35*128/2) = 22, DUR = round(30*100/1000) = 3.
        full.setFreeFall(1, 0.35, 30.0)
        assertEquals(0x95, lastWriteTo(accel, LSM303Minimal.REG_INT1_CFG_A))
        assertEquals(22, lastWriteTo(accel, LSM303Minimal.REG_INT1_THS_A))
        assertEquals(3, lastWriteTo(accel, LSM303Minimal.REG_INT1_DUR_A))
        // ±16 g at 100 Hz: THS = round(0.35*128/16) = 3.
        full.setRange(16)
        full.setFreeFall(2, 0.35, 30.0)
        assertEquals(0x95, lastWriteTo(accel, LSM303Minimal.REG_INT2_CFG_A))
        assertEquals(3, lastWriteTo(accel, LSM303Minimal.REG_INT2_THS_A))
        assertEquals(3, lastWriteTo(accel, LSM303Minimal.REG_INT2_DUR_A))
    }

    @Test
    fun clickAxisMaskSpreading() {
        val accel = MockConnection()
        val full = LSM303Full(accel, newMag())
        // single=1 (X) spreads XS=bit 1; double=2 (Y) spreads YD=bit 2.
        full.setClick(1, 0, 0.5, 10.0, 20.0, 50.0)
        assertEquals(0x02, lastWriteTo(accel, LSM303Minimal.REG_CLICK_CFG_A))
        full.setClick(0, 2, 0.5, 10.0, 20.0, 50.0)
        assertEquals(0x04, lastWriteTo(accel, LSM303Minimal.REG_CLICK_CFG_A))
        // ±2 g, 100 Hz: THS=round(0.5*128/2)=32, TL=1, LAT=2, WIN=5.
        assertEquals(32, lastWriteTo(accel, LSM303Minimal.REG_CLICK_THS_A))
        assertEquals(1, lastWriteTo(accel, LSM303Minimal.REG_TIME_LIMIT_A))
        assertEquals(2, lastWriteTo(accel, LSM303Minimal.REG_TIME_LAT_A))
        assertEquals(5, lastWriteTo(accel, LSM303Minimal.REG_TIME_WIN_A))
        assertThrows(IllegalArgumentException::class.java) { full.setClick(8, 0, 0.5, 10.0, 20.0, 50.0) }
    }

    @Test
    fun interruptRoutingCtrl3Ctrl6() {
        val accel = MockConnection()
        val full = LSM303Full(accel, newMag())
        full.enableInterrupt(LSM303Full.SOURCE_DATA_READY, 1)
        assertEquals(0x10, lastWriteTo(accel, LSM303Minimal.REG_CTRL3_A))
        full.enableInterrupt(LSM303Full.SOURCE_IA1, 2)
        // CTRL6 |= I2_INT1 (0x40) -> 0x40
        assertEquals(0x40, lastWriteTo(accel, LSM303Minimal.REG_CTRL6_A))
        // SOURCE_MAG_DATA_READY is on the dedicated DRDY pin and is not routable.
        assertThrows(IllegalArgumentException::class.java) {
            full.enableInterrupt(LSM303Full.SOURCE_MAG_DATA_READY, 1)
        }
        // SOURCE_BOOT is INT2-only.
        assertThrows(IllegalArgumentException::class.java) {
            full.enableInterrupt(LSM303Full.SOURCE_BOOT, 1)
        }
        full.enableInterrupt(LSM303Full.SOURCE_BOOT, 2)
        // CTRL6 |= BOOT_I1 (0x10) -> 0x50
        assertEquals(0x50, lastWriteTo(accel, LSM303Minimal.REG_CTRL6_A))
        full.disableInterrupt(LSM303Full.SOURCE_IA1, 2)
        // CTRL6 &= ~0x40 -> 0x10
        assertEquals(0x10, lastWriteTo(accel, LSM303Minimal.REG_CTRL6_A))
    }

    @Test
    fun pollInterruptCombinedStatus() {
        val accel = MockConnection()
        val mag = newMag()
        val full = LSM303Full(accel, mag)
        full.enableInterrupt(LSM303Full.SOURCE_DATA_READY, 1)
        full.enableInterrupt(LSM303Full.SOURCE_WATERMARK, 1)
        full.enableInterrupt(LSM303Full.SOURCE_IA1, 1)
        full.enableInterrupt(LSM303Full.SOURCE_IA2, 1)
        full.enableInterrupt(LSM303Full.SOURCE_CLICK, 1)
        accel.setRegister(LSM303Minimal.REG_STATUS_A, 0x08)          // ZYXDA
        accel.setRegister(LSM303Minimal.REG_FIFO_SRC_A, 0x80)        // WTM, not OVRN
        accel.setRegister(LSM303Minimal.REG_INT1_SRC_A, 0x40)        // IA
        accel.setRegister(LSM303Minimal.REG_INT2_SRC_A, 0x40)        // IA
        accel.setRegister(LSM303Minimal.REG_CLICK_SRC_A, 0x40)       // IA
        mag.setRegister(LSM303Minimal.REG_SR_M, 0x01)                // DRDY
        assertEquals(0xBB, full.pollInterrupt())
    }

    @Test
    fun magGainRateAndModeRegisters() {
        val accel = MockConnection()
        val mag = newMag()
        val full = LSM303Full(accel, mag)
        full.setMagRange(400)
        // (3 + 1) << 5 = 0x80
        assertEquals(0x80, lastWriteTo(mag, LSM303Minimal.REG_CRB_M))
        full.setMagDataRate(30.0)
        // (0x10 & 0x80) | (5 << 2) = 0x14
        assertEquals(0x14, lastWriteTo(mag, LSM303Minimal.REG_CRA_M))
        full.setMagMode(2)
        assertEquals(0x02, lastWriteTo(mag, LSM303Minimal.REG_MR_M))
        full.setMagMode(0)
        assertEquals(0x00, lastWriteTo(mag, LSM303Minimal.REG_MR_M))
        assertThrows(IllegalArgumentException::class.java) { full.setMagMode(3) }
        assertThrows(IllegalArgumentException::class.java) { full.setMagRange(999) }
        assertThrows(IllegalArgumentException::class.java) { full.setMagDataRate(4.0) }
    }

    @Test
    fun temperatureEnableAndConversion() {
        val accel = MockConnection()
        val mag = newMag()
        val full = LSM303Full(accel, mag)
        // raw = 0x0800 >> 4 = 128 -> 128 / 8 = 16 °C.
        mag.setRegister(LSM303Minimal.REG_TEMP_H_M, 0x08, 0x00)
        assertEquals(16.0, full.readTemperatureDelta(), 1e-9)
        // TEMP_EN set by RMW: 0x10 | 0x80 = 0x90.
        assertEquals(0x90, lastWriteTo(mag, LSM303Minimal.REG_CRA_M))
        full.enableTemperature(false)
        assertEquals(0x10, lastWriteTo(mag, LSM303Minimal.REG_CRA_M))
    }

    @Test
    fun headingFlatFourtyFive() {
        val accel = MockConnection()
        val mag = newMag()
        val full = LSM303Full(accel, mag)
        // X = Y counts (both use the XY gain) -> atan2(1, 1) = 45°.
        mag.setRegister(LSM303Minimal.REG_OUT_X_H_M, 0x02, 0x26, 0x00, 0x00, 0x02, 0x26)
        assertEquals(45.0, full.heading(false), 1e-6)
    }
}
