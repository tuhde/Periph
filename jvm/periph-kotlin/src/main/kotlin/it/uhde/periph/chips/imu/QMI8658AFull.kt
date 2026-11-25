package it.uhde.periph.chips.imu

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * QMI8658A — 6-axis inertial measurement unit (QST), full driver extending [QMI8658AMinimal].
 *
 * Adds raw and burst reads, temperature, sample counter, full-scale / ODR / filter configuration,
 * power control, SyncSample data locking, the FIFO, the motion engines (any / no / significant
 * motion, tap, pedometer, wake-on-motion), offsets, calibration-on-demand, self-test, device info,
 * pull-up control and interrupts.
 */
open class QMI8658AFull @JvmOverloads constructor(connection: RegisterConnection) : QMI8658AMinimal(connection) {

    companion object {
        /** Interrupt source: new sample (only while the FIFO is in bypass; INT2). */
        const val SOURCE_DATA_READY = 0x0001
        /** Interrupt source: FIFO watermark. */
        const val SOURCE_FIFO_WATERMARK = 0x0002
        /** Interrupt source: FIFO full. */
        const val SOURCE_FIFO_FULL = 0x0004
        /** Interrupt source: any-motion event. */
        const val SOURCE_ANY_MOTION = 0x0008
        /** Interrupt source: no-motion event. */
        const val SOURCE_NO_MOTION = 0x0010
        /** Interrupt source: significant-motion event. */
        const val SOURCE_SIGNIFICANT_MOTION = 0x0020
        /** Interrupt source: pedometer step event. */
        const val SOURCE_STEP = 0x0040
        /** Interrupt source: tap event. */
        const val SOURCE_TAP = 0x0080
        /** Interrupt source: wake-on-motion event. */
        const val SOURCE_WAKE_ON_MOTION = 0x0100

        private const val CMD_ACK = 0x00
        private const val CMD_RST_FIFO = 0x04
        private const val CMD_REQ_FIFO = 0x05
        private const val CMD_WRITE_WOM = 0x08
        private const val CMD_ACCEL_OFFSET = 0x09
        private const val CMD_GYRO_OFFSET = 0x0A
        private const val CMD_CONFIG_TAP = 0x0C
        private const val CMD_CONFIG_PEDOMETER = 0x0D
        private const val CMD_CONFIG_MOTION = 0x0E
        private const val CMD_RESET_PEDOMETER = 0x0F
        private const val CMD_COPY_USID = 0x10
        private const val CMD_SET_RPU = 0x11
        private const val CMD_AHB_CLOCK_GATING = 0x12
        private const val CMD_COD = 0xA2
        private const val CMD_APPLY_GYRO_GAINS = 0xAA
    }

    /** One coherent acceleration / angular-rate / temperature sample. */
    class Sample(
        /** Acceleration {x, y, z} in m/s². */
        @JvmField val accel: DoubleArray,
        /** Angular rate {x, y, z} in rad/s. */
        @JvmField val gyro: DoubleArray,
        /** Die temperature in °C. */
        @JvmField val tempC: Double
    )

    /** Last tap event: [count] 0 none / 1 single / 2 double, [axis] 0 none / 1 X / 2 Y / 3 Z, [negative] polarity. */
    class TapStatus(@JvmField val count: Int, @JvmField val axis: Int, @JvmField val negative: Boolean)

    /** Self-test result: [passed] and the response [values] in m/s² (accelerometer) or rad/s (gyroscope). */
    class SelfTestResult(@JvmField val passed: Boolean, @JvmField val values: DoubleArray)

    /** Calibration-on-demand result with the raw [codStatus] register. */
    class Calibration(@JvmField val success: Boolean, @JvmField val codStatus: Int)

    /** Revision, 3-byte [firmware] version and 6-byte [usid]. */
    class DeviceInfo(@JvmField val revision: Int, @JvmField val firmware: ByteArray, @JvmField val usid: ByteArray)

    private var anyThr = 0.25
    private var noThr = 0.125
    private var anyWin = 3
    private var noWin = 30
    private var anyAxes = 0x07
    private var noAxes = 0x07
    private var anyAnd = false
    private var noAnd = true
    private var sigWait = 150
    private var sigConfirm = 600
    private var intSources = 0
    private var fifoPin = 2
    private var activityPin = 2
    private var womPin = 1
    private var callback: IntConsumer? = null
    private var intPin: InputPin? = null
    private var pollThread: Thread? = null
    @Volatile private var polling = false
    private val edgeHandler = EdgeHandler { handleEdge() }

    private fun writeCtrl1(v: Int) { ctrl1 = v and 0xFF; w(REG_CTRL1, ctrl1) }
    private fun writeCtrl7(v: Int) { ctrl7 = v and 0xFF; w(REG_CTRL7, ctrl7) }
    private fun writeCtrl8(v: Int) { ctrl8 = v and 0xFF; w(REG_CTRL8, ctrl8) }

    private fun waitStatus(mask: Int, want: Boolean, timeoutMs: Long, what: String) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val st = r1(REG_STATUSINT)
            if (((st and mask) != 0) == want) return
            if (System.currentTimeMillis() > deadline) throw IOException("QMI8658A timeout waiting for $what")
            sleepMs(1)
        }
    }

    private fun ctrl9(cmd: Int, timeoutMs: Long) {
        w(REG_CTRL9, cmd)
        var failure: IOException? = null
        try {
            waitStatus(0x80, true, timeoutMs, "CTRL9 CmdDone")
        } catch (e: IOException) {
            failure = e
        }
        w(REG_CTRL9, CMD_ACK)
        if (failure != null) throw failure
        waitStatus(0x80, false, timeoutMs, "CTRL9 ack")
    }

    private fun calCmd(cmd: Int, params: IntArray, timeoutMs: Long = 100) {
        for (i in 0 until 8) w(REG_CAL1_L + i, if (i < params.size) params[i] else 0)
        ctrl9(cmd, timeoutMs)
    }

    private fun twoSets(cmd: Int, set1: IntArray, set2: IntArray) {
        val p1 = IntArray(8)
        set1.copyInto(p1)
        p1[7] = 0x10
        calCmd(cmd, p1)
        val p2 = IntArray(8)
        set2.copyInto(p2)
        p2[7] = 0x20
        calCmd(cmd, p2)
    }

    private fun sensorsOff(): Int {
        val prev = ctrl7 and 0x03
        if (prev != 0) {
            writeCtrl7(ctrl7 and 0x03.inv())
            sleepMs(5)
        }
        return prev
    }

    private fun sensorsRestore(prev: Int) {
        if (prev != 0) {
            writeCtrl7(ctrl7 or prev)
            sleepMs(if ((prev and 0x02) != 0) 165 else 10)
        }
    }

    override fun readData(reg: Int, length: Int): ByteArray {
        if (sync) {
            waitStatus(0x01, true, 100, "SyncSample Avail")
            waitStatus(0x02, true, 100, "SyncSample Locked")
        }
        return connection.read(reg, length)
    }

    /** Read raw acceleration counts. @return signed 16-bit {x, y, z} */
    fun accelRaw(): IntArray {
        val b = readData(REG_AX_L, 6)
        return intArrayOf(le16(b, 0), le16(b, 2), le16(b, 4))
    }

    /** Read raw angular-rate counts. @return signed 16-bit {x, y, z} */
    fun gyroRaw(): IntArray {
        val b = readData(REG_GX_L, 6)
        return intArrayOf(le16(b, 0), le16(b, 2), le16(b, 4))
    }

    /** Burst-read temperature, acceleration and angular rate so all three belong to one sample. */
    fun readAll(): Sample {
        val b = readData(REG_TEMP_L, 14)
        val a = DoubleArray(3) { le16(b, 2 + 2 * it) / accelSens * G }
        val g = DoubleArray(3) { le16(b, 8 + 2 * it) / gyroSens * DEG_TO_RAD }
        return Sample(a, g, le16(b, 0) / 256.0)
    }

    /** Read the die temperature. @return temperature in °C */
    fun temperature(): Double = le16(readData(REG_TEMP_L, 2), 0) / 256.0

    /** Read the 24-bit sample counter. @return counter value, wraps at 0xFFFFFF */
    fun timestamp(): Int {
        val b = connection.read(REG_TIMESTAMP, 3)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16)
    }

    /** Check for new data (`STATUS0` aDA / gDA; `STATUSINT.Avail` while SyncSample is on). */
    fun dataReady(): Boolean =
        if (sync) (r1(REG_STATUSINT) and 0x01) != 0 else (r1(REG_STATUS0) and 0x03) != 0

    /**
     * Configure the accelerometer (CTRL2).
     *
     * @param fullScale 0 = ±2 g, 1 = ±4 g, 2 = ±8 g, 3 = ±16 g
     * @param odr ODR code 0–8 or 12–15 (12–15 only with the gyroscope off)
     */
    fun configureAccel(fullScale: Int, odr: Int) {
        require(fullScale in 0..3) { "fullScale must be 0-3" }
        require(odr in 0..8 || odr in 12..15) { "odr must be 0-8 or 12-15" }
        accelFs = fullScale
        accelOdr = odr
        updateSens()
        w(REG_CTRL2, (fullScale shl 4) or odr)
    }

    /**
     * Configure the gyroscope (CTRL3).
     *
     * @param fullScale 0 = ±16 dps … 7 = ±2048 dps (doubling)
     * @param odr ODR code 0–8
     */
    fun configureGyro(fullScale: Int, odr: Int) {
        require(fullScale in 0..7) { "fullScale must be 0-7" }
        require(odr in 0..8) { "odr must be 0-8" }
        gyroFs = fullScale
        gyroOdr = odr
        updateSens()
        w(REG_CTRL3, (fullScale shl 4) or odr)
    }

    /**
     * Configure the low-pass filters (CTRL5); modes 0–3 = 2.66 / 3.63 / 5.39 / 13.37 % of ODR.
     */
    fun configureFilters(accelFilter: Boolean, accelMode: Int, gyroFilter: Boolean, gyroMode: Int) {
        require(accelMode in 0..3 && gyroMode in 0..3) { "filter mode must be 0-3" }
        w(REG_CTRL5, (gyroMode shl 5) or (if (gyroFilter) 0x10 else 0) or (accelMode shl 1) or (if (accelFilter) 1 else 0))
    }

    /**
     * Enable or disable the sensors (CTRL7 aEN / gEN / gSN). The gyroscope needs 150 ms + 3/ODR,
     * the accelerometer 3 ms + 3/ODR before data is valid.
     */
    fun setSensors(accel: Boolean, gyro: Boolean, gyroSnooze: Boolean) {
        writeCtrl7((ctrl7 and 0x13.inv()) or (if (accel) 0x01 else 0) or (if (gyro) 0x02 else 0) or
            (if (gyroSnooze) 0x10 else 0))
    }

    /** Power the chip down (CTRL1.SensorDisable, sensors disabled first) or wake it. */
    fun setPowerDown(down: Boolean) {
        if (down) {
            writeCtrl7(ctrl7 and 0x03.inv())
            writeCtrl1(ctrl1 or 0x01)
        } else {
            writeCtrl1(ctrl1 and 0x01.inv())
            sleepMs(15)
        }
    }

    /** Enable or disable SyncSample (data-locking) mode; incompatible with the FIFO and motion engines. */
    fun setSyncSample(enabled: Boolean) {
        if (enabled) {
            calCmd(CMD_AHB_CLOCK_GATING, intArrayOf(1))
            writeCtrl7(ctrl7 or 0x80)
        } else {
            writeCtrl7(ctrl7 and 0x80.inv())
            calCmd(CMD_AHB_CLOCK_GATING, intArrayOf(0))
        }
        sync = enabled
    }

    /**
     * Configure the FIFO.
     *
     * @param mode 0 = bypass, 1 = FIFO (stops when full), 2 = stream
     * @param size 0–3 = 16 / 32 / 64 / 128 samples
     * @param watermark watermark in samples (0 disables)
     */
    fun configureFifo(mode: Int, size: Int, watermark: Int) {
        require(mode in 0..2 && size in 0..3) { "mode must be 0-2 and size 0-3" }
        fifoWtm = watermark and 0xFF
        w(REG_FIFO_WTM_TH, fifoWtm)
        fifoCtrl = (size shl 2) or mode
        w(REG_FIFO_CTRL, fifoCtrl)
        if (mode == 0) writeCtrl7(ctrl7 and 0x20.inv())   // DRDY back on
    }

    /** Read the raw `FIFO_STATUS` byte (full 7, watermark 6, overflow 5, not-empty 4). */
    fun fifoStatus(): Int = r1(REG_FIFO_STATUS)

    /** Number of bytes in the FIFO (2 × the sample-word count). */
    fun fifoCount(): Int {
        val st = r1(REG_FIFO_STATUS)
        val lo = r1(REG_FIFO_SMPL_CNT)
        return 2 * (((st and 0x03) shl 8) or lo)
    }

    /**
     * Drain the FIFO with the read-mode procedure (little-endian 6-byte frames, accelerometer first
     * when both sensors are on).
     *
     * @param count maximum number of bytes, or a negative value for all available
     */
    fun readFifo(count: Int): ByteArray {
        val total = fifoCount()
        val n = if (count < 0) total else min(count, total)
        if (n <= 0) return ByteArray(0)
        ctrl9(CMD_REQ_FIFO, 100)
        w(REG_CTRL1, ctrl1 and 0x40.inv())            // ADDR_AI off: FIFO_DATA repeats
        val out = ByteArray(n)
        var got = 0
        var failure: IOException? = null
        try {
            while (got < n) {
                val chunk = min(32, n - got)
                connection.read(REG_FIFO_DATA, chunk).copyInto(out, got)
                got += chunk
            }
        } catch (e: IOException) {
            failure = e
        }
        w(REG_CTRL1, ctrl1)
        w(REG_FIFO_CTRL, fifoCtrl and 0x7F)
        if (failure != null) throw failure
        return out
    }

    /** Reset the FIFO (CTRL9 RST_FIFO). */
    fun resetFifo() = ctrl9(CMD_RST_FIFO, 100)

    private fun clampU8(v: Long): Int = max(0L, min(255L, v)).toInt()

    private fun sendMotion() {
        val prev = sensorsOff()
        try {
            val a = clampU8((anyThr * 32).roundToLong())
            val n = clampU8((noThr * 32).roundToLong())
            val mode = (if (noAnd) 0x80 else 0) or ((noAxes and 7) shl 4) or (if (anyAnd) 0x08 else 0) or (anyAxes and 7)
            twoSets(CMD_CONFIG_MOTION,
                intArrayOf(a, a, a, n, n, n, mode),
                intArrayOf(anyWin and 0xFF, noWin and 0xFF, sigWait and 0xFF, (sigWait shr 8) and 0xFF,
                    sigConfirm and 0xFF, (sigConfirm shr 8) and 0xFF))
        } finally {
            sensorsRestore(prev)
        }
    }

    /**
     * Configure the any-motion engine.
     *
     * @param thresholdG slope threshold in g (1/32 g steps, 0–7.97)
     * @param window window in samples
     * @param axes axis mask (bit 0 X, 1 Y, 2 Z)
     * @param logicAnd AND instead of OR over the enabled axes
     */
    fun configureAnyMotion(thresholdG: Double, window: Int, axes: Int, logicAnd: Boolean) {
        anyThr = thresholdG; anyWin = window; anyAxes = axes; anyAnd = logicAnd
        sendMotion()
    }

    /** Configure the no-motion engine (arguments as for [configureAnyMotion]). */
    fun configureNoMotion(thresholdG: Double, window: Int, axes: Int, logicAnd: Boolean) {
        noThr = thresholdG; noWin = window; noAxes = axes; noAnd = logicAnd
        sendMotion()
    }

    /** Configure the significant-motion engine (16-bit windows in samples); any- and no-motion must be configured and enabled. */
    fun configureSignificantMotion(waitWindow: Int, confirmWindow: Int) {
        sigWait = waitWindow; sigConfirm = confirmWindow
        sendMotion()
    }

    /** Enable the motion engines (CTRL8 bits 1–3). */
    fun enableMotion(anyMotion: Boolean, noMotion: Boolean, significant: Boolean) {
        writeCtrl8((ctrl8 and 0x0E.inv()) or (if (anyMotion) 0x02 else 0) or (if (noMotion) 0x04 else 0) or
            (if (significant) 0x08 else 0))
    }

    /**
     * Configure the tap engine (two CTRL9 CONFIGURE_TAP commands).
     *
     * @param priority axis priority 0–5
     * @param peakWindow peak window in samples
     * @param tapWindow quiet window in samples
     * @param doubleTapWindow double-tap window in samples
     * @param alpha acceleration averaging weight (1/128 steps)
     * @param gamma magnitude averaging weight (1/128 steps)
     * @param peakThreshold peak threshold in g² (1/1024 steps)
     * @param quietThreshold undefined-motion threshold in g² (1/1024 steps)
     */
    fun configureTap(priority: Int, peakWindow: Int, tapWindow: Int, doubleTapWindow: Int, alpha: Double,
                     gamma: Double, peakThreshold: Double, quietThreshold: Double) {
        val peak = (peakThreshold * 1024).roundToInt()
        val quiet = (quietThreshold * 1024).roundToInt()
        val prev = sensorsOff()
        try {
            twoSets(CMD_CONFIG_TAP,
                intArrayOf(peakWindow and 0xFF, priority and 0xFF, tapWindow and 0xFF, (tapWindow shr 8) and 0xFF,
                    doubleTapWindow and 0xFF, (doubleTapWindow shr 8) and 0xFF),
                intArrayOf((alpha * 128).roundToInt() and 0x7F, (gamma * 128).roundToInt() and 0x7F,
                    peak and 0xFF, (peak shr 8) and 0xFF, quiet and 0xFF, (quiet shr 8) and 0xFF))
        } finally {
            sensorsRestore(prev)
        }
    }

    /** Enable or disable the tap engine (CTRL8.Tap_EN). Use an accelerometer ODR above 200 Hz. */
    fun enableTap(enabled: Boolean) = writeCtrl8(if (enabled) ctrl8 or 0x01 else ctrl8 and 0x01.inv())

    /** Read the last tap event. */
    fun tapStatus(): TapStatus {
        val v = r1(REG_TAP_STATUS)
        return TapStatus(v and 0x03, (v shr 4) and 0x03, (v and 0x80) != 0)
    }

    /**
     * Configure the pedometer (two CTRL9 CONFIGURE_PEDOMETER commands; defaults are for a 50 Hz ODR).
     *
     * @param reportEvery steps per pedometer event / count update
     */
    fun configurePedometer(sampleCount: Int, peakToPeakMg: Double, peakMg: Double, timeUp: Int, timeLow: Int,
                           entryCount: Int, precision: Int, reportEvery: Int) {
        val p2p = (peakToPeakMg / 1000.0 * 1024).roundToInt()
        val pk = (peakMg / 1000.0 * 1024).roundToInt()
        val prev = sensorsOff()
        try {
            twoSets(CMD_CONFIG_PEDOMETER,
                intArrayOf(sampleCount and 0xFF, (sampleCount shr 8) and 0xFF, p2p and 0xFF, (p2p shr 8) and 0xFF,
                    pk and 0xFF, (pk shr 8) and 0xFF),
                intArrayOf(timeUp and 0xFF, (timeUp shr 8) and 0xFF, timeLow and 0xFF, entryCount and 0xFF,
                    precision and 0xFF, reportEvery and 0xFF))
        } finally {
            sensorsRestore(prev)
        }
    }

    /** Enable or disable the pedometer (CTRL8.Pedo_EN); a 0→1 transition resets the count. */
    fun enablePedometer(enabled: Boolean) = writeCtrl8(if (enabled) ctrl8 or 0x10 else ctrl8 and 0x10.inv())

    /** Read the 24-bit step count. */
    fun stepCount(): Int {
        val b = connection.read(REG_STEP_CNT, 3)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16)
    }

    /** Clear the step count (CTRL9 RESET_PEDOMETER). */
    fun resetStepCount() = ctrl9(CMD_RESET_PEDOMETER, 100)

    /**
     * Enter or leave wake-on-motion mode. Uses accelerometer ±2 g at ODR code 12 (128 Hz).
     *
     * @param thresholdMg 1–255 mg enables, 0 exits WoM mode
     * @param interruptPin 1 or 2
     * @param initialLevel initial pin level, 0 or 1
     * @param blankingSamples samples ignored after enabling (0–63)
     */
    fun setWakeOnMotion(thresholdMg: Int, interruptPin: Int, initialLevel: Int, blankingSamples: Int) {
        sensorsOff()
        if (thresholdMg == 0) {
            calCmd(CMD_WRITE_WOM, intArrayOf(0, 0))
            intSources = intSources and SOURCE_WAKE_ON_MOTION.inv()
            updateIntPins()
            return
        }
        accelFs = 0
        accelOdr = 12
        updateSens()
        w(REG_CTRL2, 0x0C)
        womPin = if (interruptPin == 2) 2 else 1
        val sel = (if (womPin == 2) 0x40 else 0) or (if (initialLevel != 0) 0x80 else 0)
        calCmd(CMD_WRITE_WOM, intArrayOf(min(255, thresholdMg), sel or (blankingSamples and 0x3F)))
        intSources = intSources or SOURCE_WAKE_ON_MOTION
        updateIntPins()
        writeCtrl7((ctrl7 and 0x03.inv()) or 0x01)
        sleepMs(10)
    }

    private fun offsetCmd(cmd: Int, codes: IntArray) {
        val prev = sensorsOff()
        try {
            val p = IntArray(6)
            for (i in 0 until 3) {
                p[2 * i] = codes[i] and 0xFF
                p[2 * i + 1] = (codes[i] shr 8) and 0xFF
            }
            calCmd(cmd, p)
        } finally {
            sensorsRestore(prev)
        }
    }

    private fun code(v: Double): Int = v.roundToLong().toInt() and 0xFFFF

    /** Apply an accelerometer offset in m/s² (signed 4.12 g; volatile). */
    fun setAccelOffset(x: Double, y: Double, z: Double) =
        offsetCmd(CMD_ACCEL_OFFSET, intArrayOf(code(x / G * 4096), code(y / G * 4096), code(z / G * 4096)))

    /** Apply a gyroscope offset in rad/s (signed 11.5 dps; volatile). */
    fun setGyroOffset(x: Double, y: Double, z: Double) =
        offsetCmd(CMD_GYRO_OFFSET, intArrayOf(code(x / DEG_TO_RAD * 32), code(y / DEG_TO_RAD * 32), code(z / DEG_TO_RAD * 32)))

    /** Run gyroscope calibration-on-demand (about 1.5 s; keep the device still). */
    fun calibrateGyro(): Calibration {
        val prev = sensorsOff()
        val status: Int
        try {
            ctrl9(CMD_COD, 3000)
            status = r1(REG_COD_STATUS)
        } finally {
            sensorsRestore(prev)
        }
        return Calibration(status == 0, status)
    }

    /** Read the raw X/Y/Z gyroscope gains, valid right after [calibrateGyro]. */
    fun getGyroGains(): IntArray {
        val b = connection.read(REG_DVX_L, 6)
        return IntArray(3) { (b[2 * it].toInt() and 0xFF) or ((b[2 * it + 1].toInt() and 0xFF) shl 8) }
    }

    /** Restore previously saved gyroscope gains. */
    fun setGyroGains(x: Int, y: Int, z: Int) {
        val prev = sensorsOff()
        try {
            calCmd(CMD_APPLY_GYRO_GAINS,
                intArrayOf(x and 0xFF, (x shr 8) and 0xFF, y and 0xFF, (y shr 8) and 0xFF, z and 0xFF, (z shr 8) and 0xFF))
        } finally {
            sensorsRestore(prev)
        }
    }

    private fun selfTest(ctrlReg: Int, saved: Int, scale: Double): DoubleArray {
        val prev = sensorsOff()
        val b: ByteArray
        try {
            w(ctrlReg, saved or 0x80)
            waitStatus(0x01, true, 3000, "self-test Avail")
            b = connection.read(REG_DVX_L, 6)
            w(ctrlReg, saved)
            waitStatus(0x01, false, 3000, "self-test end")
        } finally {
            sensorsRestore(prev)
        }
        return DoubleArray(3) { le16(b, 2 * it) / scale }
    }

    /** Run the accelerometer self-test (device at rest): passes when every axis exceeds 200 mg; values in m/s². */
    fun selfTestAccel(): SelfTestResult {
        val g = selfTest(REG_CTRL2, (accelFs shl 4) or accelOdr, 2048.0)
        return SelfTestResult(g.all { abs(it) > 0.2 }, DoubleArray(3) { g[it] * G })
    }

    /** Run the gyroscope self-test: passes when every axis exceeds 300 dps; values in rad/s. */
    fun selfTestGyro(): SelfTestResult {
        val d = selfTest(REG_CTRL3, (gyroFs shl 4) or gyroOdr, 16.0)
        return SelfTestResult(d.all { abs(it) > 300.0 }, DoubleArray(3) { d[it] * DEG_TO_RAD })
    }

    /** Read revision, firmware version and USID (CTRL9 COPY_USID). */
    fun deviceInfo(): DeviceInfo {
        ctrl9(CMD_COPY_USID, 100)
        val revision = r1(REG_REVISION_ID)
        val fw = ByteArray(3)
        connection.read(REG_FW_VERSION, 2).copyInto(fw)
        fw[2] = r1(REG_FW_VERSION + 2).toByte()
        return DeviceInfo(revision, fw, connection.read(REG_DVX_L, 6))
    }

    /**
     * Control the internal 200 kΩ pull-ups (CTRL9 SET_RPU); `false` disables a group.
     * Disabling [i2c] on a bus without external pull-ups breaks the bus.
     */
    fun setPullups(aux: Boolean, sdx: Boolean, cs: Boolean, i2c: Boolean) {
        calCmd(CMD_SET_RPU, intArrayOf((if (aux) 0 else 1) or (if (sdx) 0 else 2) or (if (cs) 0 else 4) or (if (i2c) 0 else 8)))
    }

    /** Soft reset and wait 15 ms; the sensors are off afterwards. */
    fun reset() {
        w(REG_RESET, RESET_CMD)
        sleepMs(15)
        ctrl1 = 0x40; ctrl7 = 0x00; ctrl8 = 0x80
        w(REG_CTRL1, ctrl1)
        w(REG_CTRL8, ctrl8)
        sync = false
        intSources = 0
    }

    private fun sourcePin(source: Int): Int = when (source) {
        SOURCE_DATA_READY -> 2
        SOURCE_FIFO_WATERMARK, SOURCE_FIFO_FULL -> fifoPin
        SOURCE_WAKE_ON_MOTION -> womPin
        else -> activityPin
    }

    private fun engineBit(source: Int): Int = when (source) {
        SOURCE_ANY_MOTION -> 0x02
        SOURCE_NO_MOTION -> 0x04
        SOURCE_SIGNIFICANT_MOTION -> 0x08
        SOURCE_STEP -> 0x10
        SOURCE_TAP -> 0x01
        else -> 0
    }

    private fun updateIntPins() {
        var pins = 0
        for (bit in 0 until 9) {
            val s = 1 shl bit
            if ((intSources and s) != 0) pins = pins or (1 shl (sourcePin(s) - 1))
        }
        var v = ctrl1 and 0x18.inv()
        v = v or (if ((pins and 1) != 0) 0x08 else 0) or (if ((pins and 2) != 0) 0x10 else 0)
        v = if (fifoPin == 1) v or 0x04 else v and 0x04.inv()
        if (v != ctrl1) writeCtrl1(v)
    }

    /** Route the FIFO interrupt (CTRL1.FIFO_INT_SEL) and the motion-engine group (CTRL8.ACTIVITY_INT_SEL) to INT1 (1) or INT2 (2). */
    fun setInterruptPins(fifoPin: Int, activityPin: Int) {
        this.fifoPin = if (fifoPin == 1) 1 else 2
        this.activityPin = if (activityPin == 1) 1 else 2
        writeCtrl8(if (this.activityPin == 1) ctrl8 or 0x40 else ctrl8 and 0x40.inv())
        updateIntPins()
    }

    /** Enable one interrupt source (a `SOURCE_*` constant). */
    fun enableInterrupt(source: Int) {
        when {
            source == SOURCE_DATA_READY -> writeCtrl7(ctrl7 and 0x20.inv())
            source == SOURCE_FIFO_WATERMARK -> w(REG_FIFO_WTM_TH, fifoWtm)
            engineBit(source) != 0 -> writeCtrl8(ctrl8 or engineBit(source))
        }
        intSources = intSources or source
        updateIntPins()
    }

    /** Disable one interrupt source (a `SOURCE_*` constant). */
    fun disableInterrupt(source: Int) {
        when {
            source == SOURCE_DATA_READY -> writeCtrl7(ctrl7 or 0x20)
            source == SOURCE_FIFO_WATERMARK -> w(REG_FIFO_WTM_TH, 0)
            engineBit(source) != 0 -> writeCtrl8(ctrl8 and engineBit(source).inv())
        }
        intSources = intSources and source.inv()
        updateIntPins()
    }

    /** Read STATUS0, STATUS1 and FIFO_STATUS (clearing what clears on read). @return combined `SOURCE_*` bits */
    fun pollInterrupt(): Int {
        val s = connection.read(REG_STATUS0, 2)
        val fs = r1(REG_FIFO_STATUS)
        val s0 = s[0].toInt() and 0xFF
        val s1 = s[1].toInt() and 0xFF
        var st = 0
        if ((s0 and 0x03) != 0) st = st or SOURCE_DATA_READY
        if ((fs and 0x40) != 0) st = st or SOURCE_FIFO_WATERMARK
        if ((fs and 0x80) != 0) st = st or SOURCE_FIFO_FULL
        if ((s1 and 0x20) != 0) st = st or SOURCE_ANY_MOTION
        if ((s1 and 0x40) != 0) st = st or SOURCE_NO_MOTION
        if ((s1 and 0x80) != 0) st = st or SOURCE_SIGNIFICANT_MOTION
        if ((s1 and 0x10) != 0) st = st or SOURCE_STEP
        if ((s1 and 0x02) != 0) st = st or SOURCE_TAP
        if ((s1 and 0x04) != 0) st = st or SOURCE_WAKE_ON_MOTION
        return st
    }

    /**
     * Subscribe to rising edges on the connection's interrupt pin (a 5 ms polling thread when none
     * is attached). The callback receives the [pollInterrupt] status for a non-zero status.
     */
    fun onInterrupt(callback: IntConsumer) {
        offInterrupt()
        this.callback = callback
        val pin = connection.intPin()
        if (pin != null) {
            intPin = pin
            pin.onEdge(edgeHandler, EdgeTrigger.RISING)
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
        }, "qmi8658a-poll")
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
}
