package it.uhde.periph.chips.imu

import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * MPU-6050 — 6-axis MotionTracking device (accelerometer + gyroscope), minimal driver.
 *
 * Provides 3-axis acceleration and 3-axis angular rate readings with no
 * configuration beyond the transport. Performs device reset, WHO_AM_I check,
 * and enables all sensors at defaults during initialization.
 *
 * Default I²C address: 0x68 (AD0=GND), 0x69 (AD0=VCC).
 */
open class Mpu6050Minimal @JvmOverloads constructor(
    protected val transport: Transport
) {
    companion object {
        const val REG_SMPLRT_DIV   = 0x19
        const val REG_CONFIG       = 0x1A
        const val REG_GYRO_CONFIG  = 0x1B
        const val REG_ACCEL_CONFIG = 0x1C
        const val REG_FIFO_EN      = 0x23
        const val REG_INT_STATUS   = 0x3A
        const val REG_ACCEL_XOUT_H = 0x3B
        const val REG_TEMP_OUT_H   = 0x41
        const val REG_GYRO_XOUT_H  = 0x43
        const val REG_USER_CTRL    = 0x6A
        const val REG_PWR_MGMT_1   = 0x6B
        const val REG_PWR_MGMT_2   = 0x6C
        const val REG_FIFO_COUNTH  = 0x72
        const val REG_FIFO_R_W     = 0x74
        const val REG_WHO_AM_I     = 0x75

        const val WHO_AM_I_VALUE = 0x68

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
            throw IOException("MPU6050 WHO_AM_I: expected 0x${WHO_AM_I_VALUE.toString(16)}, got 0x${who.toString(16)}")
        }
        writeReg(REG_GYRO_CONFIG, 0x00)
        writeReg(REG_ACCEL_CONFIG, 0x00)
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
        val buf = transport.writeRead(byteArrayOf(REG_ACCEL_XOUT_H.toByte()), 6)
        val ax = ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)).toShort().toInt()
        val ay = ((buf[2].toInt() and 0xFF) shl 8 or (buf[3].toInt() and 0xFF)).toShort().toInt()
        val az = ((buf[4].toInt() and 0xFF) shl 8 or (buf[5].toInt() and 0xFF)).toShort().toInt()
        val sens = ACCEL_SENSITIVITY[accelFs]
        return doubleArrayOf(ax / sens * 9.80665, ay / sens * 9.80665, az / sens * 9.80665)
    }

    /**
     * Read 3-axis angular rate.
     *
     * @return array [x, y, z] in rad/s.
     */
    fun gyro(): DoubleArray {
        val buf = transport.writeRead(byteArrayOf(REG_GYRO_XOUT_H.toByte()), 6)
        val gx = ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)).toShort().toInt()
        val gy = ((buf[2].toInt() and 0xFF) shl 8 or (buf[3].toInt() and 0xFF)).toShort().toInt()
        val gz = ((buf[4].toInt() and 0xFF) shl 8 or (buf[5].toInt() and 0xFF)).toShort().toInt()
        val sens = GYRO_SENSITIVITY[gyroFs]
        return doubleArrayOf(gx / sens * Math.PI / 180.0,
                             gy / sens * Math.PI / 180.0,
                             gz / sens * Math.PI / 180.0)
    }

    protected fun writeReg(reg: Int, value: Int) {
        transport.write(byteArrayOf(reg.toByte(), value.toByte()))
    }

    protected fun readReg(reg: Int): Int {
        val b = transport.writeRead(byteArrayOf(reg.toByte()), 1)
        return b[0].toInt() and 0xFF
    }

    protected fun readReg16Signed(reg: Int): Int {
        val b = transport.writeRead(byteArrayOf(reg.toByte()), 2)
        return (((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)).toShort().toInt()
    }
}
