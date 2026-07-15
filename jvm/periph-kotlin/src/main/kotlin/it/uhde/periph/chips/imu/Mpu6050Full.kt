package it.uhde.periph.chips.imu

import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * MPU-6050 — full driver. Extends [Mpu6050Minimal] with configuration,
 * temperature, raw data access, data-ready polling, sleep/standby, and FIFO management.
 */
class Mpu6050Full @JvmOverloads constructor(
    transport: Transport
) : Mpu6050Minimal(transport) {

    /**
     * Set gyroscope full-scale range.
     *
     * @param fullScale range selector 0–3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
     */
    fun configureGyro(fullScale: Int = 0) {
        gyroFs = fullScale and 0x03
        writeReg(REG_GYRO_CONFIG, (fullScale and 0x03) shl 3)
    }

    /**
     * Set accelerometer full-scale range.
     *
     * @param fullScale range selector 0–3 (0=±2g, 1=±4g, 2=±8g, 3=±16g).
     */
    fun configureAccel(fullScale: Int = 0) {
        accelFs = fullScale and 0x03
        writeReg(REG_ACCEL_CONFIG, (fullScale and 0x03) shl 3)
    }

    /**
     * Set digital low-pass filter bandwidth.
     *
     * @param dlpf filter setting 0–6 (0=260/256 Hz … 6=5/5 Hz).
     */
    fun configureDlpf(dlpf: Int = 3) {
        writeReg(REG_CONFIG, dlpf and 0x07)
    }

    /**
     * Set sample rate divider.
     *
     * @param divider SMPLRT_DIV value 0–255.
     */
    fun configureSampleRate(divider: Int = 4) {
        writeReg(REG_SMPLRT_DIV, divider and 0xFF)
    }

    /**
     * Read die temperature.
     *
     * @return temperature in °C.
     */
    fun temperature(): Double {
        val raw = readReg16Signed(REG_TEMP_OUT_H)
        return raw / 340.0 + 36.53
    }

    /**
     * Read raw 3-axis accelerometer values.
     *
     * @return array [x, y, z] as raw 16-bit signed values.
     */
    fun accelRaw(): IntArray {
        val buf = transport.writeRead(byteArrayOf(REG_ACCEL_XOUT_H.toByte()), 6)
        return intArrayOf(
            ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)).toShort().toInt(),
            ((buf[2].toInt() and 0xFF) shl 8 or (buf[3].toInt() and 0xFF)).toShort().toInt(),
            ((buf[4].toInt() and 0xFF) shl 8 or (buf[5].toInt() and 0xFF)).toShort().toInt()
        )
    }

    /**
     * Read raw 3-axis gyroscope values.
     *
     * @return array [x, y, z] as raw 16-bit signed values.
     */
    fun gyroRaw(): IntArray {
        val buf = transport.writeRead(byteArrayOf(REG_GYRO_XOUT_H.toByte()), 6)
        return intArrayOf(
            ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)).toShort().toInt(),
            ((buf[2].toInt() and 0xFF) shl 8 or (buf[3].toInt() and 0xFF)).toShort().toInt(),
            ((buf[4].toInt() and 0xFF) shl 8 or (buf[5].toInt() and 0xFF)).toShort().toInt()
        )
    }

    /**
     * Check if new sensor data is available.
     *
     * @return true when DATA_RDY_INT is set in INT_STATUS.
     */
    fun dataReady(): Boolean {
        return (readReg(REG_INT_STATUS) and 0x01) != 0
    }

    /**
     * Set or clear the SLEEP bit in PWR_MGMT_1.
     *
     * @param sleep true to enter sleep mode, false to wake.
     */
    fun setSleep(sleep: Boolean = true) {
        var v = readReg(REG_PWR_MGMT_1)
        v = if (sleep) v or 0x40 else v and 0xBF
        writeReg(REG_PWR_MGMT_1, v)
    }

    /**
     * Put individual axes into standby mode.
     */
    fun setStandby(xa: Boolean = false, ya: Boolean = false, za: Boolean = false,
                   xg: Boolean = false, yg: Boolean = false, zg: Boolean = false) {
        val v = ((if (xa) 1 else 0) shl 5) or ((if (ya) 1 else 0) shl 4) or ((if (za) 1 else 0) shl 3) or
                ((if (xg) 1 else 0) shl 2) or ((if (yg) 1 else 0) shl 1) or (if (zg) 1 else 0)
        writeReg(REG_PWR_MGMT_2, v)
    }

    /**
     * Read the number of bytes in the FIFO buffer.
     *
     * @return FIFO byte count (0–1024).
     */
    fun fifoCount(): Int {
        val buf = transport.writeRead(byteArrayOf(REG_FIFO_COUNTH.toByte()), 2)
        return ((buf[0].toInt() and 0x1F) shl 8) or (buf[1].toInt() and 0xFF)
    }

    /**
     * Read all available data from the FIFO buffer.
     *
     * @return FIFO data as byte array.
     */
    fun readFifo(): ByteArray {
        val count = fifoCount()
        if (count == 0) return ByteArray(0)
        return transport.writeRead(byteArrayOf(REG_FIFO_R_W.toByte()), count)
    }

    /**
     * Configure and enable FIFO sources.
     */
    fun enableFifo(gyro: Boolean = true, accel: Boolean = true, temp: Boolean = false) {
        val fifoEn = ((if (accel) 1 else 0) shl 3) or ((if (temp) 1 else 0) shl 2) or ((if (gyro) 1 else 0) shl 4)
        writeReg(REG_FIFO_EN, fifoEn)
        val userCtrl = readReg(REG_USER_CTRL)
        writeReg(REG_USER_CTRL, userCtrl or 0x40)
    }

    /**
     * Reset the FIFO buffer by setting FIFO_RST in USER_CTRL.
     */
    fun resetFifo() {
        val userCtrl = readReg(REG_USER_CTRL)
        writeReg(REG_USER_CTRL, userCtrl or 0x04)
    }
}
