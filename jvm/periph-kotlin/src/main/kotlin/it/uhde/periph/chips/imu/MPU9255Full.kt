package it.uhde.periph.chips.imu

import it.uhde.periph.connection.RegisterConnection
import kotlin.math.abs

/**
 * MPU-9255 full interface — extends MPU9255Minimal with complete functionality.
 *
 * Adds gyroscope and accelerometer full-scale configuration, DLPF settings,
 * sample rate control, temperature reading, magnetometer (AK8963) support,
 * wake-on-motion, raw data access, data-ready polling, sleep/standby control,
 * and FIFO management.
 *
 * Default I²C address: 0x68 (AD0=GND), 0x69 (AD0=VCC).
 *
 * The AK8963 magnetometer sits behind the MPU-9255's I²C bypass (BYPASS_EN)
 * as its own device at address 0x0C, so it needs its own connection bound to
 * that address on the same bus — it cannot be reached through the connection
 * already bound to the MPU-9255's own address. Construct that second
 * connection the same way as the primary one (e.g. `I2CConnection(1, 0x0C)`
 * alongside `I2CConnection(1, 0x68)`) and pass both in.
 *
 * @param connection RegisterConnection (I²C or SMBus) pointing at the MPU-9255.
 * @param magConnection RegisterConnection (I²C or SMBus) bound to the AK8963's address
 *                      (0x0C), on the same bus as [connection].
 */
class MPU9255Full @JvmOverloads constructor(
    connection: RegisterConnection,
    private val magConnection: RegisterConnection
) : MPU9255Minimal(connection) {

    companion object {
        private const val AK8963_REG_WIA      = 0x00
        private const val AK8963_REG_ST1      = 0x02
        private const val AK8963_REG_HXL      = 0x03
        private const val AK8963_REG_ST2      = 0x09
        private const val AK8963_REG_CNTL1    = 0x0A
        private const val AK8963_REG_CNTL2    = 0x0B
        private const val AK8963_REG_ASAX     = 0x10
        private const val AK8963_REG_ASAY     = 0x11
        private const val AK8963_REG_ASAZ     = 0x12
        private const val AK8963_WIA_VALUE    = 0x48

        private const val MAG_SENSITIVITY_14BIT = 0.6
        private const val MAG_SENSITIVITY_16BIT = 0.15

        private val LPOSC_TABLE = doubleArrayOf(
            0.24, 0.49, 0.98, 1.95, 3.91, 7.81, 15.63, 31.25,
            62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0,
        )
    }

    private var magEnabled = false
    private var magBits = 16
    private var magScaleX = 1.0
    private var magScaleY = 1.0
    private var magScaleZ = 1.0

    /**
     * Set gyroscope full-scale range.
     *
     * @param fullScale Range selector 0–3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
     */
    fun configureGyro(fullScale: Int = 0) {
        gyroFs = fullScale and 0x03
        connection.write(REG_GYRO_CONFIG, byteArrayOf(((fullScale and 0x03) shl 3).toByte()))
    }

    /**
     * Set accelerometer full-scale range.
     *
     * @param fullScale Range selector 0–3 (0=±2g, 1=±4g, 2=±8g, 3=±16g).
     */
    fun configureAccel(fullScale: Int = 0) {
        accelFs = fullScale and 0x03
        connection.write(REG_ACCEL_CONFIG, byteArrayOf(((fullScale and 0x03) shl 3).toByte()))
    }

    /**
     * Set digital low-pass filter bandwidth.
     *
     * @param gyroDlpf   Gyro filter setting 0–7 (0=250 Hz, 1=184 Hz, 2=92 Hz, 3=41 Hz, 4=20 Hz, 5=10 Hz, 6=5 Hz, 7=3600 Hz).
     * @param accelDlpf  Accel filter setting 0–7 (0=218.1 Hz, 1=218.1 Hz, 2=99 Hz, 3=44.8 Hz, 4=21.2 Hz, 5=10.2 Hz, 6=5.05 Hz, 7=420 Hz).
     */
    fun configureDlpf(gyroDlpf: Int = 3, accelDlpf: Int = 3) {
        connection.write(REG_CONFIG, byteArrayOf((gyroDlpf and 0x07).toByte()))
        connection.write(REG_ACCEL_CONFIG2, byteArrayOf((accelDlpf and 0x07).toByte()))
    }

    /**
     * Set sample rate divider.
     *
     * @param divider SMPLRT_DIV value 0–255; output rate = 1 kHz / (1 + divider) when DLPF is active.
     */
    fun configureSampleRate(divider: Int = 4) {
        connection.write(REG_SMPLRT_DIV, byteArrayOf((divider and 0xFF).toByte()))
    }

    /**
     * Read die temperature.
     *
     * @return temperature in °C.
     */
    fun temperature(): Double {
        val raw = readReg16Signed(REG_TEMP_OUT_H)
        return raw.toDouble() / 333.87 + 21.0
    }

    /**
     * Initialize AK8963 magnetometer via I²C bypass mode.
     *
     * @param bits Output resolution, 14 or 16.
     * @param mode Operation mode (1=single, 2=8 Hz continuous, 6=100 Hz continuous).
     */
    fun enableMag(bits: Int = 16, mode: Int = 6) {
        connection.write(REG_INT_PIN_CFG, byteArrayOf((0x22).toByte()))
        Thread.sleep(10)

        magConnection.write(AK8963_REG_CNTL1, byteArrayOf((0x00).toByte()))
        Thread.sleep(10)

        magConnection.write(AK8963_REG_CNTL1, byteArrayOf((0x0F).toByte()))
        Thread.sleep(10)

        val asax = ak8963Read(AK8963_REG_ASAX)
        val asay = ak8963Read(AK8963_REG_ASAY)
        val asaz = ak8963Read(AK8963_REG_ASAZ)

        magScaleX = (asax - 128) / 256.0 + 1.0
        magScaleY = (asay - 128) / 256.0 + 1.0
        magScaleZ = (asaz - 128) / 256.0 + 1.0

        magConnection.write(AK8963_REG_CNTL1, byteArrayOf((0x00).toByte()))
        Thread.sleep(10)

        var cntl1Val = 0
        if (bits == 16) {
            cntl1Val = cntl1Val or 0x10
        }
        cntl1Val = cntl1Val or (mode and 0x0F)
        magConnection.write(AK8963_REG_CNTL1, byteArrayOf((cntl1Val).toByte()))
        Thread.sleep(10)

        magEnabled = true
        magBits = bits
    }

    /**
     * Read 3-axis magnetic field.
     *
     * @return array [x, y, z] in µT.
     * @throws IllegalStateException if magnetometer has not been enabled via enableMag().
     */
    fun mag(): DoubleArray {
        if (!magEnabled) {
            throw IllegalStateException("Magnetometer not enabled. Call enableMag() first.")
        }
        val buf = magConnection.read(AK8963_REG_HXL, 7)
        val mx = ((buf[1].toInt() and 0xFF) shl 8 or (buf[0].toInt() and 0xFF)).toShort().toInt()
        val my = ((buf[3].toInt() and 0xFF) shl 8 or (buf[2].toInt() and 0xFF)).toShort().toInt()
        val mz = ((buf[5].toInt() and 0xFF) shl 8 or (buf[4].toInt() and 0xFF)).toShort().toInt()
        // ST2 at buf[6] must be read to unlock next measurement

        val sens = if (magBits == 16) MAG_SENSITIVITY_16BIT else MAG_SENSITIVITY_14BIT
        return doubleArrayOf(mx.toDouble() * sens * magScaleX,
                             my.toDouble() * sens * magScaleY,
                             mz.toDouble() * sens * magScaleZ)
    }

    /**
     * Read raw 3-axis accelerometer values.
     *
     * @return array [x, y, z] as raw 16-bit signed values.
     */
    fun accelRaw(): IntArray {
        val buf = connection.read(REG_ACCEL_XOUT_H, 6)
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
        val buf = connection.read(REG_GYRO_XOUT_H, 6)
        return intArrayOf(
            ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)).toShort().toInt(),
            ((buf[2].toInt() and 0xFF) shl 8 or (buf[3].toInt() and 0xFF)).toShort().toInt(),
            ((buf[4].toInt() and 0xFF) shl 8 or (buf[5].toInt() and 0xFF)).toShort().toInt()
        )
    }

    /**
     * Read raw 3-axis magnetometer values.
     *
     * @return array [x, y, z] as raw 16-bit signed values.
     * @throws IllegalStateException if magnetometer has not been enabled via enableMag().
     */
    fun magRaw(): IntArray {
        if (!magEnabled) {
            throw IllegalStateException("Magnetometer not enabled. Call enableMag() first.")
        }
        // ST2 (buf[6]) is not used but must be read to unlock the next measurement.
        val buf = magConnection.read(AK8963_REG_HXL, 7)
        return intArrayOf(
            ((buf[1].toInt() and 0xFF) shl 8 or (buf[0].toInt() and 0xFF)).toShort().toInt(),
            ((buf[3].toInt() and 0xFF) shl 8 or (buf[2].toInt() and 0xFF)).toShort().toInt(),
            ((buf[5].toInt() and 0xFF) shl 8 or (buf[4].toInt() and 0xFF)).toShort().toInt()
        )
    }

    /**
     * Check if new sensor data is available.
     *
     * @return true when RAW_DATA_RDY_INT is set in INT_STATUS.
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
        var value = readReg(REG_PWR_MGMT_1)
        if (sleep) {
            value = value or 0x40
        } else {
            value = value and 0xFFBF
        }
        connection.write(REG_PWR_MGMT_1, byteArrayOf((value).toByte()))
    }

    /**
     * Read the number of bytes in the FIFO buffer.
     *
     * @return FIFO byte count (0–512).
     */
    fun fifoCount(): Int {
        val buf = connection.read(REG_FIFO_COUNTH, 2)
        return ((buf[0].toInt() and 0x1F) shl 8) or (buf[1].toInt() and 0xFF)
    }

    /**
     * Read all available data from the FIFO buffer.
     *
     * @return FIFO data bytes.
     */
    fun readFifo(): ByteArray {
        val count = fifoCount()
        if (count == 0) return byteArrayOf()
        return connection.read(REG_FIFO_R_W, count)
    }

    /**
     * Configure and enable FIFO sources.
     *
     * @param gyro  Enable gyroscope data in FIFO.
     * @param accel Enable accelerometer data in FIFO.
     * @param temp  Enable temperature data in FIFO.
     */
    fun enableFifo(gyro: Boolean = true, accel: Boolean = true, temp: Boolean = false) {
        val fifoEn = ((if (accel) 1 else 0) shl 3) or ((if (temp) 1 else 0) shl 2) or ((if (gyro) 1 else 0) shl 4)
        connection.write(REG_FIFO_EN, byteArrayOf((fifoEn).toByte()))
        val userCtrl = readReg(REG_USER_CTRL)
        connection.write(REG_USER_CTRL, byteArrayOf((userCtrl or 0x40).toByte()))
    }

    /**
     * Reset the FIFO buffer by setting FIFO_RST in USER_CTRL.
     */
    fun resetFifo() {
        val userCtrl = readReg(REG_USER_CTRL)
        connection.write(REG_USER_CTRL, byteArrayOf((userCtrl or 0x04).toByte()))
    }

    /**
     * Configure wake-on-motion and enter accelerometer-only low-power mode.
     *
     * @param thresholdMg Motion threshold in milligrams (4–1020 mg, quantized to 4 mg steps).
     * @param odrHz       Wake-up output data rate in Hz (0.24–500 Hz).
     */
    fun configureWakeOnMotion(thresholdMg: Int = 64, odrHz: Float = 31.25f) {
        var thresholdLsb = (thresholdMg + 2) / 4
        if (thresholdLsb < 1) thresholdLsb = 1
        if (thresholdLsb > 255) thresholdLsb = 255

        var bestSel = 0
        var bestDiff = abs(odrHz - LPOSC_TABLE[0])
        for (sel in 1 until LPOSC_TABLE.size) {
            val diff = abs(odrHz - LPOSC_TABLE[sel])
            if (diff < bestDiff) {
                bestDiff = diff
                bestSel = sel
            }
        }

        connection.write(REG_PWR_MGMT_1, byteArrayOf((0x01).toByte()))
        connection.write(REG_PWR_MGMT_2, byteArrayOf((0x07).toByte()))
        connection.write(REG_ACCEL_CONFIG2, byteArrayOf((0x01).toByte()))
        connection.write(REG_INT_ENABLE, byteArrayOf((0x40).toByte()))
        connection.write(REG_MOT_DETECT_CTRL, byteArrayOf((0xC0).toByte()))
        connection.write(REG_WOM_THR, byteArrayOf((thresholdLsb and 0xFF).toByte()))
        connection.write(REG_LP_ACCEL_ODR, byteArrayOf((bestSel and 0x0F).toByte()))
        connection.write(REG_PWR_MGMT_1, byteArrayOf((0x21).toByte()))
    }

    /**
     * Check if a wake-on-motion interrupt has fired.
     *
     * @return true when WOM_INT (bit 6) is set in INT_STATUS. Reading
     *         INT_STATUS clears the interrupt.
     */
    fun motionDetected(): Boolean {
        return (readReg(REG_INT_STATUS) and 0x40) != 0
    }

    private fun ak8963Read(reg: Int): Int {
        val b = magConnection.read(reg, 1)
        return b[0].toInt() and 0xFF
    }

}