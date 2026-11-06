package it.uhde.periph.chips.imu

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * ICM-20948 full driver — extends [ICM20948Minimal] with the complete chip functionality.
 *
 * Adds raw and burst reads, temperature, full-scale / filter / sample-rate configuration, sensor
 * power control, low-power modes, gyro and accelerometer offsets, the AK09916 magnetometer (read
 * through the chip's own I²C master — no second connection is needed), single-byte auxiliary-bus
 * access, the 4 KiB FIFO, wake-on-motion, interrupts, self-test and the PLL time-base correction.
 *
 * Methods that touch banks 1–3 select the bank and return to bank 0, so the bank register is 0
 * between calls.
 */
open class ICM20948Full @JvmOverloads constructor(connection: RegisterConnection) : ICM20948Minimal(connection) {

    companion object {
        /** Interrupt source: new raw data (`INT_STATUS_1.RAW_DATA_0_RDY_INT`). */
        const val SOURCE_DATA_READY = 0x01
        /** Interrupt source: wake on motion (`INT_STATUS.WOM_INT`). */
        const val SOURCE_WAKE_ON_MOTION = 0x02
        /** Interrupt source: I²C master transfer done. */
        const val SOURCE_I2C_MASTER = 0x04
        /** Interrupt source: PLL running. */
        const val SOURCE_PLL_READY = 0x08
        /** Interrupt source: FIFO overflow. */
        const val SOURCE_FIFO_OVERFLOW = 0x10

        const val AK_ADDR = 0x0C
        const val AK_WIA2 = 0x01
        const val AK_ST1 = 0x10
        const val AK_CNTL2 = 0x31
        const val AK_CNTL3 = 0x32
        const val AK_WIA2_VALUE = 0x09
        const val MAG_SENSITIVITY = 0.15
    }

    /** One coherent acceleration / angular-rate / temperature sample. */
    class Sample(
        /** Acceleration {x, y, z} in m/s². */
        val accel: DoubleArray,
        /** Angular rate {x, y, z} in rad/s. */
        val gyro: DoubleArray,
        /** Die temperature in °C. */
        val tempC: Double
    )

    private var magEnabled = false
    private var intActiveLow = false
    private var callback: IntConsumer? = null
    private var intPin: InputPin? = null
    private var pollThread: Thread? = null
    @Volatile private var polling = false
    private val edgeHandler = EdgeHandler { handleEdge() }

    private fun lpSuspend() {
        if (lpEn && !lpSuspended) {
            val v = rd1(0, REG_PWR_MGMT_1)
            wr1(0, REG_PWR_MGMT_1, v and 0x20.inv())
            lpSuspended = true
        }
    }

    private fun lpResume() {
        if (lpSuspended) {
            val v = rd1(0, REG_PWR_MGMT_1)
            wr1(0, REG_PWR_MGMT_1, v or 0x20)
            lpSuspended = false
        }
    }

    /** Read raw 3-axis accelerometer values (signed 16-bit counts). */
    fun accelRaw(): IntArray {
        val b = rd(0, REG_ACCEL_XOUT_H, 6)
        return intArrayOf(be16(b, 0), be16(b, 2), be16(b, 4))
    }

    /** Read raw 3-axis gyroscope values (signed 16-bit counts). */
    fun gyroRaw(): IntArray {
        val b = rd(0, REG_GYRO_XOUT_H, 6)
        return intArrayOf(be16(b, 0), be16(b, 2), be16(b, 4))
    }

    /** Burst-read acceleration, angular rate and temperature so all three belong to one sample. */
    fun readAll(): Sample {
        val b = rd(0, REG_ACCEL_XOUT_H, 14)
        val aSens = ACCEL_SENSITIVITY[accelFs]
        val gSens = GYRO_SENSITIVITY[gyroFs]
        val a = DoubleArray(3) { be16(b, 2 * it) / aSens * G }
        val g = DoubleArray(3) { be16(b, 6 + 2 * it) / gSens * DEG_TO_RAD }
        return Sample(a, g, be16(b, 12) / 333.87 + 21.0)
    }

    /** Read the die temperature in °C. */
    fun temperature(): Double = be16(rd(0, REG_TEMP_OUT_H, 2), 0) / 333.87 + 21.0

    /** Set the gyroscope full-scale range: 0 = ±250, 1 = ±500, 2 = ±1000, 3 = ±2000 dps. */
    fun configureGyro(fullScale: Int) {
        lpSuspend()
        gyroFs = fullScale and 0x03
        writeGyroConfig()
        lpResume()
    }

    /** Set the accelerometer full-scale range: 0 = ±2, 1 = ±4, 2 = ±8, 3 = ±16 g. */
    fun configureAccel(fullScale: Int) {
        lpSuspend()
        accelFs = fullScale and 0x03
        writeAccelConfig()
        lpResume()
    }

    /**
     * Set the digital low-pass filters.
     *
     * @param gyroDlpf GYRO_DLPFCFG 0–7 (3 = 51.2 Hz)
     * @param accelDlpf ACCEL_DLPFCFG 0–7 (3 = 50.4 Hz)
     * @param gyroFilter false bypasses the gyro filter (9 kHz output rate)
     * @param accelFilter false bypasses the accelerometer filter (4.5 kHz)
     */
    fun configureFilters(gyroDlpf: Int, accelDlpf: Int, gyroFilter: Boolean, accelFilter: Boolean) {
        lpSuspend()
        this.gyroDlpf = gyroDlpf and 0x07
        this.accelDlpf = accelDlpf and 0x07
        this.gyroFilter = gyroFilter
        this.accelFilter = accelFilter
        writeGyroConfig()
        writeAccelConfig()
        lpResume()
    }

    /**
     * Set the sample-rate dividers; ODR = 1125 Hz / (1 + div).
     *
     * @param gyroDiv GYRO_SMPLRT_DIV 0–255
     * @param accelDiv ACCEL_SMPLRT_DIV 0–4095
     */
    fun configureSampleRate(gyroDiv: Int, accelDiv: Int) {
        lpSuspend()
        wr1(2, REG_GYRO_SMPLRT_DIV, gyroDiv and 0xFF)
        wr1(2, REG_ACCEL_SMPLRT_DIV_1, (accelDiv shr 8) and 0x0F)
        wr1(2, REG_ACCEL_SMPLRT_DIV_2, accelDiv and 0xFF)
        lpResume()
    }

    /**
     * Switch the accelerometer and gyroscope on or off (`PWR_MGMT_2`). Turning a sensor on again
     * needs its start-up time (accel 20 ms, gyro 35 ms).
     */
    fun setSensors(accel: Boolean, gyro: Boolean) {
        wr1(0, REG_PWR_MGMT_2, (if (accel) 0 else 0x38) or (if (gyro) 0 else 0x07))
    }

    /** Set or clear the `SLEEP` bit in `PWR_MGMT_1`. */
    fun setSleep(sleep: Boolean) {
        val v = rd1(0, REG_PWR_MGMT_1)
        wr1(0, REG_PWR_MGMT_1, if (sleep) v or 0x40 else v and 0x40.inv())
    }

    /**
     * Configure duty-cycled low-power operation; `LP_EN` is set last because it blocks most
     * register writes.
     *
     * @param accelAvg DEC3_CFG averaging code 0–3
     * @param gyroAvg GYRO_AVGCFG averaging code 0–7
     */
    fun setLowPower(accel: Boolean, gyro: Boolean, accelAvg: Int, gyroAvg: Int) {
        lpSuspended = false
        val v = rd1(0, REG_PWR_MGMT_1) and 0x20.inv()
        wr1(0, REG_PWR_MGMT_1, v)
        wr1(2, REG_ACCEL_CONFIG_2, accelAvg and 0x03)
        wr1(2, REG_GYRO_CONFIG_2, gyroAvg and 0x07)
        wr1(0, REG_LP_CONFIG, 0x40 or (if (accel) 0x20 else 0) or (if (gyro) 0x10 else 0))
        lpEn = accel || gyro
        if (lpEn) wr1(0, REG_PWR_MGMT_1, v or 0x20)
    }

    /** Write the gyroscope offset-cancellation registers (rad/s; 0.0305 dps/LSB, signed 16-bit). */
    fun setGyroOffset(x: Double, y: Double, z: Double) {
        val data = ByteArray(6)
        doubleArrayOf(x, y, z).forEachIndexed { i, v ->
            val code = max(-32768L, min(32767L, (v / DEG_TO_RAD / 0.0305).roundToLong()))
            data[2 * i] = (code shr 8).toByte()
            data[2 * i + 1] = code.toByte()
        }
        lpSuspend()
        wr(2, REG_XG_OFFS_USRH, data)
        lpResume()
    }

    /**
     * Read the 15-bit accelerometer offset codes (0.98 mg/LSB). The registers hold a per-part
     * factory trim: read, adjust, write back.
     *
     * @return {x, y, z} signed 15-bit offset codes
     */
    fun getAccelOffset(): IntArray {
        val raw = rd(1, REG_XA_OFFS_H, 8)
        return IntArray(3) { i ->
            val code = ((raw[3 * i].toInt() and 0xFF) shl 7) or ((raw[3 * i + 1].toInt() and 0xFF) shr 1)
            Register.toSigned(code, 15)
        }
    }

    /** Write the 15-bit accelerometer offset codes. */
    fun setAccelOffset(x: Int, y: Int, z: Int) {
        lpSuspend()
        val regs = intArrayOf(0x14, 0x17, 0x1A)
        val vals = intArrayOf(x, y, z)
        for (i in 0 until 3) {
            val code = vals[i] and 0x7FFF
            wr(1, regs[i], byteArrayOf(((code shr 7) and 0xFF).toByte(), ((code shl 1) and 0xFE).toByte()))
        }
        lpResume()
    }

    private fun slv4Wait() {
        for (i in 0 until 100) {
            val status = rd1(0, REG_I2C_MST_STATUS)
            if ((status and 0x10) != 0) throw IOException("ICM20948 auxiliary I2C NACK")
            if ((status and 0x40) != 0) return
            sleepMs(1)
        }
        throw IOException("ICM20948 auxiliary I2C transfer timed out")
    }

    /**
     * Read one byte from an auxiliary-bus device through I²C-master slave 4.
     *
     * @param address 7-bit device address
     * @param register register inside the device
     * @return the byte read
     */
    fun auxReadByte(address: Int, register: Int): Int {
        selectBank(3)
        connection.write(REG_I2C_SLV4_ADDR, byteArrayOf((0x80 or (address and 0x7F)).toByte()))
        connection.write(REG_I2C_SLV4_REG, byteArrayOf(register.toByte()))
        connection.write(REG_I2C_SLV4_CTRL, byteArrayOf(0x80.toByte()))
        selectBank(0)
        slv4Wait()
        return rd1(3, REG_I2C_SLV4_DI)
    }

    /**
     * Write one byte to an auxiliary-bus device through I²C-master slave 4.
     *
     * @param address 7-bit device address
     * @param register register inside the device
     * @param value byte to write
     */
    fun auxWriteByte(address: Int, register: Int, value: Int) {
        selectBank(3)
        connection.write(REG_I2C_SLV4_ADDR, byteArrayOf((address and 0x7F).toByte()))
        connection.write(REG_I2C_SLV4_REG, byteArrayOf(register.toByte()))
        connection.write(REG_I2C_SLV4_DO, byteArrayOf(value.toByte()))
        connection.write(REG_I2C_SLV4_CTRL, byteArrayOf(0x80.toByte()))
        selectBank(0)
        slv4Wait()
    }

    /**
     * Enable the AK09916 magnetometer via the I²C master. Resets the magnetometer, verifies `WIA2`
     * == 0x09, selects continuous measurement 4 (100 Hz) and configures slave 0 to copy the 9-byte
     * `ST1..ST2` block to `EXT_SLV_SENS_DATA_00..08` continuously.
     *
     * @throws IOException if `WIA2` does not read 0x09 or an auxiliary transfer fails
     */
    fun enableMag() {
        lpSuspend()
        val ctrl = rd1(0, REG_USER_CTRL)
        wr1(0, REG_USER_CTRL, ctrl or 0x20)                 // I2C_MST_EN
        wr1(3, REG_I2C_MST_CTRL, 0x07)                      // 345.6 kHz
        auxWriteByte(AK_ADDR, AK_CNTL3, 0x01)               // soft reset
        sleepMs(2)
        val wia = auxReadByte(AK_ADDR, AK_WIA2)
        if (wia != AK_WIA2_VALUE) {
            throw IOException("AK09916 WIA2: expected 0x09, got 0x${wia.toString(16)}")
        }
        auxWriteByte(AK_ADDR, AK_CNTL2, 0x08)               // continuous 4
        selectBank(3)
        connection.write(REG_I2C_SLV0_ADDR, byteArrayOf((0x80 or AK_ADDR).toByte()))
        connection.write(REG_I2C_SLV0_REG, byteArrayOf(AK_ST1.toByte()))
        connection.write(REG_I2C_SLV0_CTRL, byteArrayOf(0x89.toByte()))
        selectBank(0)
        magEnabled = true
        lpResume()
    }

    /** Write the magnetometer `CNTL2` mode through slave 4: 0 power-down, 1 single, 2/4/6 continuous 1–3, 8 continuous 4. */
    fun magSetMode(mode: Int) {
        auxWriteByte(AK_ADDR, AK_CNTL2, mode and 0x1F)
    }

    private fun magBlock(): ByteArray {
        check(magEnabled) { "Magnetometer not enabled. Call enableMag() first." }
        return rd(0, REG_EXT_SLV_SENS_DATA_00, 9)
    }

    /**
     * Read the magnetic field.
     *
     * @return {x, y, z} in µT, as the chip reports them
     * @throws IllegalStateException if [enableMag] has not been called
     * @throws IOException if the sensor overflowed (HOFL)
     */
    fun mag(): DoubleArray {
        val b = magBlock()
        if ((b[8].toInt() and 0x08) != 0) throw IOException("Magnetometer overflow (HOFL)")
        return doubleArrayOf(le16(b, 1) * MAG_SENSITIVITY, le16(b, 3) * MAG_SENSITIVITY,
            le16(b, 5) * MAG_SENSITIVITY)
    }

    /** Read the raw magnetometer values (signed 16-bit counts). */
    fun magRaw(): IntArray {
        val b = magBlock()
        return intArrayOf(le16(b, 1), le16(b, 3), le16(b, 5))
    }

    /** Check the magnetometer `DRDY` flag from the shadowed block. */
    fun magDataReady(): Boolean = (magBlock()[0].toInt() and 0x01) != 0

    /** Check whether new raw data is available (`INT_STATUS_1.RAW_DATA_0_RDY_INT`). */
    fun dataReady(): Boolean = (rd1(0, REG_INT_STATUS_1) and 0x01) != 0

    /** Select the FIFO sources, enable the FIFO and reset it. */
    fun enableFifo(accel: Boolean, gyro: Boolean, temp: Boolean) {
        lpSuspend()
        wr1(0, REG_FIFO_EN_2, (if (accel) 0x10 else 0) or (if (gyro) 0x0E else 0) or (if (temp) 0x01 else 0))
        val ctrl = rd1(0, REG_USER_CTRL)
        wr1(0, REG_USER_CTRL, ctrl or 0x40)
        resetFifo()
        lpResume()
    }

    /** Select the FIFO mode: false = stream (overwrite oldest), true = snapshot (stop when full). */
    fun setFifoMode(snapshot: Boolean) {
        lpSuspend()
        wr1(0, REG_FIFO_MODE, if (snapshot) 0x1F else 0x00)
        lpResume()
    }

    /** Read the number of bytes in the FIFO (0–4096). */
    fun fifoCount(): Int {
        val b = rd(0, REG_FIFO_COUNTH, 2)
        return ((b[0].toInt() and 0x1F) shl 8) or (b[1].toInt() and 0xFF)
    }

    /**
     * Read bytes from the FIFO (the frame layout is not documented in the datasheet).
     *
     * @param count maximum bytes to read; negative reads everything available
     */
    fun readFifo(count: Int): ByteArray {
        val available = fifoCount()
        val remaining = if (count < 0 || count > available) available else count
        val out = ByteArray(remaining)
        var done = 0
        while (done < remaining) {
            val n = min(remaining - done, 32)
            rd(0, REG_FIFO_R_W, n).copyInto(out, done)
            done += n
        }
        return out
    }

    /** Reset the FIFO by asserting and de-asserting `FIFO_RST`. */
    fun resetFifo() {
        wr1(0, REG_FIFO_RST, 0x1F)
        wr1(0, REG_FIFO_RST, 0x00)
    }

    /**
     * Configure wake-on-motion (needs the accelerometer on with its filter enabled).
     *
     * @param thresholdMg threshold in mg (4 mg/LSB, 0–1020)
     * @param enabled true to arm, false to disarm
     */
    fun setWakeOnMotion(thresholdMg: Double, enabled: Boolean) {
        lpSuspend()
        val thr = max(0L, min(255L, (thresholdMg / 4.0).roundToLong())).toInt()
        wr1(2, REG_ACCEL_WOM_THR, thr)
        wr1(2, REG_ACCEL_INTEL_CTRL, if (enabled) 0x03 else 0x00)
        if (enabled) enableInterrupt(SOURCE_WAKE_ON_MOTION) else disableInterrupt(SOURCE_WAKE_ON_MOTION)
        lpResume()
    }

    /** Configure the INT1 pin (`INT_PIN_CFG`). */
    fun setInterruptPin(activeLow: Boolean, openDrain: Boolean, latch: Boolean, anyReadClears: Boolean) {
        intActiveLow = activeLow
        wr1(0, REG_INT_PIN_CFG, (if (activeLow) 0x80 else 0) or (if (openDrain) 0x40 else 0) or
            (if (latch) 0x20 else 0) or (if (anyReadClears) 0x10 else 0))
    }

    private fun intEnable(source: Int, on: Boolean) {
        val (reg, mask) = when (source) {
            SOURCE_DATA_READY -> Pair(REG_INT_ENABLE_1, 0x01)
            SOURCE_WAKE_ON_MOTION -> Pair(REG_INT_ENABLE, 0x08)
            SOURCE_I2C_MASTER -> Pair(REG_INT_ENABLE, 0x01)
            SOURCE_PLL_READY -> Pair(REG_INT_ENABLE, 0x04)
            SOURCE_FIFO_OVERFLOW -> Pair(REG_INT_ENABLE_2, 0x1F)
            else -> throw IllegalArgumentException("ICM20948: unknown interrupt source 0x${source.toString(16)}")
        }
        val v = rd1(0, reg)
        wr1(0, reg, if (on) v or mask else v and mask.inv())
    }

    /** Enable one interrupt source (a `SOURCE_*` constant). */
    fun enableInterrupt(source: Int) = intEnable(source, true)

    /** Disable one interrupt source (a `SOURCE_*` constant). */
    fun disableInterrupt(source: Int) = intEnable(source, false)

    /** Read (and clear) `INT_STATUS`, `INT_STATUS_1` and `INT_STATUS_2`; returns the combined `SOURCE_*` bits. */
    fun pollInterrupt(): Int {
        val s = rd(0, REG_INT_STATUS, 3)
        var status = 0
        if ((s[1].toInt() and 0x01) != 0) status = status or SOURCE_DATA_READY
        if ((s[0].toInt() and 0x08) != 0) status = status or SOURCE_WAKE_ON_MOTION
        if ((s[0].toInt() and 0x01) != 0) status = status or SOURCE_I2C_MASTER
        if ((s[0].toInt() and 0x04) != 0) status = status or SOURCE_PLL_READY
        if ((s[2].toInt() and 0x1F) != 0) status = status or SOURCE_FIFO_OVERFLOW
        return status
    }

    /**
     * Subscribe to INT1 assertions. The callback receives the [pollInterrupt] status and is only
     * invoked for a non-zero status. Delivery uses the connection's `intPin()` when one is wired
     * and falls back to a 5 ms polling thread otherwise.
     */
    fun onInterrupt(callback: IntConsumer) {
        offInterrupt()
        this.callback = callback
        val pin = connection.intPin()
        if (pin != null) {
            intPin = pin
            pin.onEdge(edgeHandler, if (intActiveLow) EdgeTrigger.FALLING else EdgeTrigger.RISING)
        } else {
            startPolling()
        }
    }

    /** Unsubscribe and stop the interrupt delivery. */
    fun offInterrupt() {
        callback = null
        intPin?.offEdge(edgeHandler)
        intPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun startPolling() {
        polling = true
        val t = Thread({
            while (polling) {
                handleEdge()
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "icm20948-poll")
        t.isDaemon = true
        pollThread = t
        t.start()
    }

    private fun handleEdge() {
        try {
            val status = pollInterrupt()
            val cb = callback
            if (status != 0 && cb != null) cb.accept(status)
        } catch (ignored: IOException) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }

    private fun averageRaw(): Array<DoubleArray> {
        val samples = 5
        val sa = DoubleArray(3)
        val sg = DoubleArray(3)
        repeat(samples) {
            val a = accelRaw()
            val g = gyroRaw()
            for (i in 0 until 3) {
                sa[i] += a[i].toDouble()
                sg[i] += g[i].toDouble()
            }
            sleepMs(11)
        }
        for (i in 0 until 3) {
            sa[i] /= samples
            sg[i] /= samples
        }
        return arrayOf(sa, sg)
    }

    /**
     * Run the self-test enable procedure and return the raw response. Configures ±2 g / ±250 dps,
     * averages samples with and without the self-test actuation and restores the configuration.
     * The datasheet gives no pass/fail limits, so none is evaluated.
     *
     * @return {accel {x, y, z}, gyro {x, y, z}} raw response (enabled minus disabled)
     */
    fun selfTest(): Array<DoubleArray> {
        val savedAccel = accelFs
        val savedGyro = gyroFs
        configureAccel(0)
        configureGyro(0)
        sleepMs(50)
        val off = averageRaw()
        lpSuspend()
        wr1(2, REG_GYRO_CONFIG_2, 0x38)
        wr1(2, REG_ACCEL_CONFIG_2, 0x1C)
        sleepMs(50)
        val on = averageRaw()
        wr1(2, REG_GYRO_CONFIG_2, 0x00)
        wr1(2, REG_ACCEL_CONFIG_2, 0x00)
        lpResume()
        configureAccel(savedAccel)
        configureGyro(savedGyro)
        return Array(2) { k -> DoubleArray(3) { i -> on[k][i] - off[k][i] } }
    }

    /** Read the per-part PLL time-base correction in percent (0.079 %/LSB, signed). */
    fun readTimebaseCorrection(): Double =
        Register.toSigned(rd1(1, REG_TIMEBASE_CORRECTION_PLL), 8) * 0.079

    /**
     * Issue `DEVICE_RESET` and wait. The chip is left in sleep with default registers; create a
     * new driver afterwards to run the initialization again.
     */
    fun reset() {
        wr1(0, REG_PWR_MGMT_1, 0x80)
        sleepMs(100)
        bank = 0
        lpEn = false
        lpSuspended = false
        magEnabled = false
    }
}
