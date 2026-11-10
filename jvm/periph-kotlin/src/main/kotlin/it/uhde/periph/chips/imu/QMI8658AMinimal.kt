package it.uhde.periph.chips.imu

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * QMI8658A — 6-axis inertial measurement unit (QST), minimal driver.
 *
 * Provides 3-axis acceleration and 3-axis angular rate with no configuration beyond the
 * connection. The constructor runs the datasheet initialisation sequence: soft reset, `WHO_AM_I`
 * check, address auto-increment on, little-endian reads, CTRL9 handshake by polling, both sensors
 * on. I3C and 3-wire SPI are out of scope.
 *
 * I²C address: 0x6B (SA0=GND), 0x6A (SA0=VDDIO); SPI uses read bit 0x80 and no multi-byte bit.
 *
 * ## Configuration defaults
 * - Accelerometer ±2 g, gyroscope ±256 dps
 * - Both output data rates code 5 (224.2 Hz in 6DOF mode)
 * - Both low-pass filters on, mode 3 (13.37 % of ODR)
 * - FIFO bypass (data-ready active), all INT pins high-Z, engines off
 *
 * @throws IOException on bus error or if `WHO_AM_I` is not 0x05
 */
open class QMI8658AMinimal @JvmOverloads constructor(
    protected val connection: RegisterConnection
) {
    companion object {
        const val REG_WHO_AM_I      = 0x00
        const val REG_REVISION_ID   = 0x01
        const val REG_CTRL1         = 0x02
        const val REG_CTRL2         = 0x03
        const val REG_CTRL3         = 0x04
        const val REG_CTRL5         = 0x06
        const val REG_CTRL7         = 0x08
        const val REG_CTRL8         = 0x09
        const val REG_CTRL9         = 0x0A
        const val REG_CAL1_L        = 0x0B
        const val REG_FIFO_WTM_TH   = 0x13
        const val REG_FIFO_CTRL     = 0x14
        const val REG_FIFO_SMPL_CNT = 0x15
        const val REG_FIFO_STATUS   = 0x16
        const val REG_FIFO_DATA     = 0x17
        const val REG_STATUSINT     = 0x2D
        const val REG_STATUS0       = 0x2E
        const val REG_TIMESTAMP     = 0x30
        const val REG_TEMP_L        = 0x33
        const val REG_AX_L          = 0x35
        const val REG_GX_L          = 0x3B
        const val REG_COD_STATUS    = 0x46
        const val REG_FW_VERSION    = 0x49
        const val REG_RESET_STATUS  = 0x4D
        const val REG_DVX_L         = 0x51
        const val REG_TAP_STATUS    = 0x59
        const val REG_STEP_CNT      = 0x5A
        const val REG_RESET         = 0x60

        const val WHO_AM_I_VALUE = 0x05
        const val RESET_CMD = 0xB0

        const val G = 9.80665
        const val DEG_TO_RAD = Math.PI / 180.0
    }

    protected var ctrl1 = 0x40
    protected var ctrl7 = 0x03
    protected var ctrl8 = 0x80
    protected var accelFs = 0
    protected var gyroFs = 4
    protected var accelOdr = 5
    protected var gyroOdr = 5
    protected var accelSens = 16384.0
    protected var gyroSens = 128.0
    protected var fifoCtrl = 0
    protected var fifoWtm = 0
    protected var sync = false

    init {
        sleepMs(15)
        w(REG_RESET, RESET_CMD)
        sleepMs(15)
        r1(REG_RESET_STATUS)                      // 0x80 after reset, informational
        val who = r1(REG_WHO_AM_I)
        if (who != WHO_AM_I_VALUE) {
            throw IOException("QMI8658A WHO_AM_I: expected 0x05, got 0x${who.toString(16)}")
        }
        w(REG_CTRL1, ctrl1)                       // ADDR_AI = 1, BE = 0
        w(REG_CTRL8, ctrl8)                       // CTRL9 handshake by polling
        updateSens()
        w(REG_CTRL2, 0x05)
        w(REG_CTRL3, 0x45)
        w(REG_CTRL5, 0x77)
        w(REG_CTRL7, ctrl7)
        sleepMs(165)                              // gyroscope turn-on
    }

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun w(reg: Int, value: Int) = connection.write(reg, byteArrayOf(value.toByte()))

    protected fun r1(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    protected fun updateSens() {
        accelSens = (16384 shr accelFs).toDouble()
        gyroSens = (2048 shr gyroFs).toDouble()
    }

    /** Read sensor-data registers; [QMI8658AFull] overrides this for SyncSample locking. */
    protected open fun readData(reg: Int, length: Int): ByteArray = connection.read(reg, length)

    /** Signed little-endian 16-bit value at [offset]. */
    protected fun le16(b: ByteArray, offset: Int): Int =
        (((b[offset + 1].toInt() and 0xFF) shl 8) or (b[offset].toInt() and 0xFF)).toShort().toInt()

    /**
     * Read 3-axis linear acceleration.
     *
     * @return {x, y, z} acceleration in m/s²
     */
    fun accel(): DoubleArray {
        val b = readData(REG_AX_L, 6)
        return doubleArrayOf(le16(b, 0) / accelSens * G, le16(b, 2) / accelSens * G, le16(b, 4) / accelSens * G)
    }

    /**
     * Read 3-axis angular rate.
     *
     * @return {x, y, z} angular rate in rad/s
     */
    fun gyro(): DoubleArray {
        val b = readData(REG_GX_L, 6)
        return doubleArrayOf(le16(b, 0) / gyroSens * DEG_TO_RAD, le16(b, 2) / gyroSens * DEG_TO_RAD,
            le16(b, 4) / gyroSens * DEG_TO_RAD)
    }
}
