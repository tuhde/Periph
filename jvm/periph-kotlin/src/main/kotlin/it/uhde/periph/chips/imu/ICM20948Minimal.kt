package it.uhde.periph.chips.imu

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * ICM-20948 — 9-axis MotionTracking device (TDK InvenSense), minimal driver.
 *
 * Provides 3-axis acceleration and 3-axis angular rate with no configuration
 * beyond the connection. The registers are organised in four user banks selected
 * through `REG_BANK_SEL` (0x7F); the driver caches the selected bank and always
 * returns to bank 0 after a bank 1–3 access. VDDIO is 1.71–1.95 V only. SPI and
 * the DMP are out of scope. The magnetometer is part of [ICM20948Full].
 *
 * I²C address: 0x68 (AD0=GND), 0x69 (AD0=VDDIO).
 *
 * ## Configuration defaults
 * - Gyroscope ±250 dps, low-pass filter 51.2 Hz (GYRO_DLPFCFG=3)
 * - Accelerometer ±2 g, low-pass filter 50.4 Hz (ACCEL_DLPFCFG=3)
 * - Both output data rates 102.3 Hz (divider 10)
 * - Clock auto (CLKSEL=1), all six axes and the temperature sensor on
 * - FIFO, DMP, I²C master and all interrupts off
 *
 * @throws IOException on bus error or if `WHO_AM_I` is not 0xEA
 */
open class ICM20948Minimal @JvmOverloads constructor(
    protected val connection: RegisterConnection
) {
    companion object {
        // Bank 0
        const val REG_WHO_AM_I         = 0x00
        const val REG_USER_CTRL        = 0x03
        const val REG_LP_CONFIG        = 0x05
        const val REG_PWR_MGMT_1       = 0x06
        const val REG_PWR_MGMT_2       = 0x07
        const val REG_INT_PIN_CFG      = 0x0F
        const val REG_INT_ENABLE       = 0x10
        const val REG_INT_ENABLE_1     = 0x11
        const val REG_INT_ENABLE_2     = 0x12
        const val REG_I2C_MST_STATUS   = 0x17
        const val REG_INT_STATUS       = 0x19
        const val REG_INT_STATUS_1     = 0x1A
        const val REG_ACCEL_XOUT_H     = 0x2D
        const val REG_GYRO_XOUT_H      = 0x33
        const val REG_TEMP_OUT_H       = 0x39
        const val REG_EXT_SLV_SENS_DATA_00 = 0x3B
        const val REG_FIFO_EN_2        = 0x67
        const val REG_FIFO_RST         = 0x68
        const val REG_FIFO_MODE        = 0x69
        const val REG_FIFO_COUNTH      = 0x70
        const val REG_FIFO_R_W         = 0x72
        const val REG_BANK_SEL         = 0x7F

        // Bank 1
        const val REG_XA_OFFS_H        = 0x14
        const val REG_TIMEBASE_CORRECTION_PLL = 0x28

        // Bank 2
        const val REG_GYRO_SMPLRT_DIV    = 0x00
        const val REG_GYRO_CONFIG_1      = 0x01
        const val REG_GYRO_CONFIG_2      = 0x02
        const val REG_XG_OFFS_USRH       = 0x03
        const val REG_ACCEL_SMPLRT_DIV_1 = 0x10
        const val REG_ACCEL_SMPLRT_DIV_2 = 0x11
        const val REG_ACCEL_INTEL_CTRL   = 0x12
        const val REG_ACCEL_WOM_THR      = 0x13
        const val REG_ACCEL_CONFIG       = 0x14
        const val REG_ACCEL_CONFIG_2     = 0x15

        // Bank 3
        const val REG_I2C_MST_CTRL  = 0x01
        const val REG_I2C_SLV0_ADDR = 0x03
        const val REG_I2C_SLV0_REG  = 0x04
        const val REG_I2C_SLV0_CTRL = 0x05
        const val REG_I2C_SLV4_ADDR = 0x13
        const val REG_I2C_SLV4_REG  = 0x14
        const val REG_I2C_SLV4_CTRL = 0x15
        const val REG_I2C_SLV4_DO   = 0x16
        const val REG_I2C_SLV4_DI   = 0x17

        const val WHO_AM_I_VALUE = 0xEA

        val ACCEL_SENSITIVITY = doubleArrayOf(16384.0, 8192.0, 4096.0, 2048.0)
        val GYRO_SENSITIVITY  = doubleArrayOf(131.0, 65.5, 32.8, 16.4)
        const val G = 9.80665
        const val DEG_TO_RAD = Math.PI / 180.0
    }

    protected var bank = 0
    protected var accelFs = 0
    protected var gyroFs = 0
    protected var accelDlpf = 3
    protected var gyroDlpf = 3
    protected var accelFilter = true
    protected var gyroFilter = true
    protected var lpEn = false
    protected var lpSuspended = false

    init {
        selectBank(0, true)
        val who = rd1(0, REG_WHO_AM_I)
        if (who != WHO_AM_I_VALUE) {
            throw IOException("ICM20948 WHO_AM_I: expected 0x${WHO_AM_I_VALUE.toString(16)}, got 0x${who.toString(16)}")
        }
        wr1(0, REG_PWR_MGMT_1, 0x80)      // DEVICE_RESET
        sleepMs(100)
        bank = 0                          // reset returns to bank 0
        wr1(0, REG_PWR_MGMT_1, 0x01)      // SLEEP = 0, CLKSEL = 1
        wr1(0, REG_PWR_MGMT_2, 0x00)      // all axes on
        writeGyroConfig()
        writeAccelConfig()
        wr1(2, REG_GYRO_SMPLRT_DIV, 0x0A)
        wr1(2, REG_ACCEL_SMPLRT_DIV_1, 0x00)
        wr1(2, REG_ACCEL_SMPLRT_DIV_2, 0x0A)
        sleepMs(35)
    }

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun selectBank(newBank: Int, force: Boolean = false) {
        if (force || newBank != bank) {
            connection.write(REG_BANK_SEL, byteArrayOf((newBank shl 4).toByte()))
            bank = newBank
        }
    }

    protected fun rd(b: Int, reg: Int, length: Int): ByteArray {
        selectBank(b)
        val data = connection.read(reg, length)
        if (b != 0) selectBank(0)
        return data
    }

    protected fun wr(b: Int, reg: Int, data: ByteArray) {
        selectBank(b)
        connection.write(reg, data)
        if (b != 0) selectBank(0)
    }

    protected fun rd1(b: Int, reg: Int): Int = rd(b, reg, 1)[0].toInt() and 0xFF

    protected fun wr1(b: Int, reg: Int, value: Int) = wr(b, reg, byteArrayOf(value.toByte()))

    protected fun writeGyroConfig() {
        wr1(2, REG_GYRO_CONFIG_1, (gyroDlpf shl 3) or (gyroFs shl 1) or (if (gyroFilter) 1 else 0))
    }

    protected fun writeAccelConfig() {
        wr1(2, REG_ACCEL_CONFIG, (accelDlpf shl 3) or (accelFs shl 1) or (if (accelFilter) 1 else 0))
    }

    /** Signed big-endian 16-bit value at [offset]. */
    protected fun be16(b: ByteArray, offset: Int): Int =
        (((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)).toShort().toInt()

    /** Signed little-endian 16-bit value at [offset]. */
    protected fun le16(b: ByteArray, offset: Int): Int =
        (((b[offset + 1].toInt() and 0xFF) shl 8) or (b[offset].toInt() and 0xFF)).toShort().toInt()

    /**
     * Read 3-axis linear acceleration.
     *
     * @return {x, y, z} acceleration in m/s²
     */
    fun accel(): DoubleArray {
        val b = rd(0, REG_ACCEL_XOUT_H, 6)
        val sens = ACCEL_SENSITIVITY[accelFs]
        return doubleArrayOf(be16(b, 0) / sens * G, be16(b, 2) / sens * G, be16(b, 4) / sens * G)
    }

    /**
     * Read 3-axis angular rate.
     *
     * @return {x, y, z} angular rate in rad/s
     */
    fun gyro(): DoubleArray {
        val b = rd(0, REG_GYRO_XOUT_H, 6)
        val sens = GYRO_SENSITIVITY[gyroFs]
        return doubleArrayOf(be16(b, 0) / sens * DEG_TO_RAD, be16(b, 2) / sens * DEG_TO_RAD,
            be16(b, 4) / sens * DEG_TO_RAD)
    }
}
