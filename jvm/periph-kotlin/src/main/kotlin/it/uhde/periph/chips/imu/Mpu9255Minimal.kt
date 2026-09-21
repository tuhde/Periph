package it.uhde.periph.chips.imu

import it.uhde.periph.connection.Connection

/**
 * MPU-9255 — 9-axis MotionTracking device (accelerometer + gyroscope), minimal driver.
 *
 * Provides 3-axis acceleration and 3-axis angular rate readings with no
 * configuration beyond the connection. Performs device reset, WHO_AM_I check,
 * and enables all sensors at defaults during initialization. Magnetometer
 * and wake-on-motion are not included in Minimal — both require non-trivial
 * secondary initialization paths.
 *
 * Default I²C address: 0x68 (AD0=GND), 0x69 (AD0=VCC).
 * The MPU-9255's WHO_AM_I register reads 0x73; the MPU-9250 reads 0x71 at
 * the same address.
 *
 * ## Configuration defaults
 * - Gyroscope full-scale: ±250 dps (GYRO_FS_SEL=0)
 * - Accelerometer full-scale: ±2 g (ACCEL_FS_SEL=0)
 * - Gyroscope DLPF: 41 Hz bandwidth (CONFIG DLPF_CFG=3)
 * - Accelerometer DLPF: 42 Hz bandwidth (ACCEL_CONFIG2 A_DLPFCFG=3)
 * - Sample rate: 200 Hz (SMPLRT_DIV=4)
 * - Clock: auto PLL (CLKSEL=1)
 * - All six axes enabled
 */
open class Mpu9255Minimal @JvmOverloads constructor(
    protected val connection: Connection
) {
    companion object {
        const val REG_SMPLRT_DIV    = 0x19
        const val REG_CONFIG        = 0x1A
        const val REG_GYRO_CONFIG   = 0x1B
        const val REG_ACCEL_CONFIG  = 0x1C
        const val REG_ACCEL_CONFIG2 = 0x1D
        const val REG_LP_ACCEL_ODR  = 0x1E
        const val REG_WOM_THR       = 0x1F
        const val REG_FIFO_EN       = 0x23
        const val REG_INT_PIN_CFG   = 0x37
        const val REG_INT_ENABLE    = 0x38
        const val REG_INT_STATUS    = 0x3A
        const val REG_ACCEL_XOUT_H  = 0x3B
        const val REG_TEMP_OUT_H    = 0x41
        const val REG_GYRO_XOUT_H   = 0x43
        const val REG_MOT_DETECT_CTRL = 0x69
        const val REG_USER_CTRL     = 0x6A
        const val REG_PWR_MGMT_1    = 0x6B
        const val REG_PWR_MGMT_2    = 0x6C
        const val REG_FIFO_COUNTH   = 0x72
        const val REG_FIFO_COUNTL   = 0x73
        const val REG_FIFO_R_W      = 0x74
        const val REG_WHO_AM_I      = 0x75

        const val WHO_AM_I_VALUE = 0x73

        val ACCEL_SENSITIVITY = doubleArrayOf(16384.0, 8192.0, 4096.0, 2048.0)
        val GYRO_SENSITIVITY  = doubleArrayOf(131.0, 65.5, 32.8, 16.4)
    }

    protected var accelFs = 0
    protected var gyroFs = 0

    init {
        writeReg(REG_PWR_MGMT_1, 0x80)
        Thread.sleep(100)
        writeReg(REG_PWR_MGMT_1, 0x01)
        val who = readReg(REG_WHO_AM_I)
        if (who != WHO_AM_I_VALUE) {
            throw java.io.IOException("MPU9255 WHO_AM_I: expected 0x${Integer.toHexString(WHO_AM_I_VALUE)}, got 0x${Integer.toHexString(who)}")
        }
        writeReg(REG_GYRO_CONFIG, 0x00)
        writeReg(REG_ACCEL_CONFIG, 0x00)
        writeReg(REG_ACCEL_CONFIG2, 0x00)
        writeReg(REG_CONFIG, 0x03)
        writeReg(REG_SMPLRT_DIV, 0x04)
        Thread.sleep(35)
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * @return array [x, y, z] in m/s².
     */
    fun accel(): DoubleArray {
        val buf = connection.writeRead(byteArrayOf(REG_ACCEL_XOUT_H.toByte()), 6)
        val ax = ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)).toShort().toInt()
        val ay = ((buf[2].toInt() and 0xFF) shl 8 or (buf[3].toInt() and 0xFF)).toShort().toInt()
        val az = ((buf[4].toInt() and 0xFF) shl 8 or (buf[5].toInt() and 0xFF)).toShort().toInt()
        val sens = ACCEL_SENSITIVITY[accelFs]
        return doubleArrayOf(ax.toDouble() / sens * 9.80665, ay.toDouble() / sens * 9.80665, az.toDouble() / sens * 9.80665)
    }

    /**
     * Read 3-axis angular rate.
     *
     * @return array [x, y, z] in rad/s.
     */
    fun gyro(): DoubleArray {
        val buf = connection.writeRead(byteArrayOf(REG_GYRO_XOUT_H.toByte()), 6)
        val gx = ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)).toShort().toInt()
        val gy = ((buf[2].toInt() and 0xFF) shl 8 or (buf[3].toInt() and 0xFF)).toShort().toInt()
        val gz = ((buf[4].toInt() and 0xFF) shl 8 or (buf[5].toInt() and 0xFF)).toShort().toInt()
        val sens = GYRO_SENSITIVITY[gyroFs]
        return doubleArrayOf(gx.toDouble() / sens * Math.PI / 180.0,
                             gy.toDouble() / sens * Math.PI / 180.0,
                             gz.toDouble() / sens * Math.PI / 180.0)
    }

    protected fun writeReg(reg: Int, value: Int) {
        connection.write(byteArrayOf(reg.toByte(), value.toByte()))
    }

    protected fun readReg(reg: Int): Int {
        val b = connection.writeRead(byteArrayOf(reg.toByte()), 1)
        return b[0].toInt() and 0xFF
    }

    protected fun readReg16Signed(reg: Int): Int {
        val b = connection.writeRead(byteArrayOf(reg.toByte()), 2)
        return ((b[0].toInt() and 0xFF) shl 8 or (b[1].toInt() and 0xFF)).toShort().toInt()
    }
}