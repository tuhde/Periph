package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * ADXL345 full interface — extends [Adxl345Minimal] with configuration, FIFO,
 * tap / activity / inactivity / free-fall detection, and interrupt routing.
 *
 * Adds range and data-rate selection, low-power mode, self-test, per-axis
 * offset calibration (in *g*), single/double-tap detection, activity and
 * inactivity detection, free-fall detection, 32-level FIFO, interrupt routing
 * (INT1 / INT2), and sleep / auto-sleep / link mode.
 */
class Adxl345Full @JvmOverloads constructor(
    connection: Connection,
    busType: Int = Adxl345Minimal.BUS_I2C
) : Adxl345Minimal(connection, busType) {

    /** Set the measurement range to ±2/±4/±8/±16 g. FULL_RES is preserved. */
    @Throws(IOException::class)
    fun setRange(rangeG: Int) {
        val code = when (rangeG) {
            2  -> 0
            4  -> 1
            8  -> 2
            16 -> 3
            else -> return
        }
        rangeBits = code
        var df = readReg(REG_DATA_FORMAT)
        df = (df and 0x03.inv()) or (code and 0x03)
        if (fullRes) df = df or 0x08
        writeReg(REG_DATA_FORMAT, df)
    }

    /** Set the output data rate to the nearest supported value (6.25 Hz–3200 Hz). */
    @Throws(IOException::class)
    fun setDataRate(rateHz: Double) {
        var bestCode = RATE_CODES[0][0]
        var bestRate = RATE_CODES[0][1]
        var bestDiff = Math.abs(bestRate - rateHz)
        for (i in 1 until RATE_CODES.size) {
            val diff = Math.abs(RATE_CODES[i][1] - rateHz)
            if (diff < bestDiff) {
                bestCode = RATE_CODES[i][0]
                bestRate = RATE_CODES[i][1]
                bestDiff = diff
            }
        }
        var bw = readReg(REG_BW_RATE)
        bw = (bw and 0x0F.inv()) or (bestCode and 0x0F)
        writeReg(REG_BW_RATE, bw)
    }

    /** Enable or disable low-power mode (higher noise). */
    @Throws(IOException::class)
    fun setLowPower(enabled: Boolean) {
        var bw = readReg(REG_BW_RATE)
        if (enabled) bw = bw or 0x10 else bw = bw and 0x10.inv()
        writeReg(REG_BW_RATE, bw)
    }

    /** Set per-axis offset in *g*. */
    @Throws(IOException::class)
    fun setOffset(x: Double, y: Double, z: Double) {
        writeReg(REG_OFSX, encodeOffset(x))
        writeReg(REG_OFSY, encodeOffset(y))
        writeReg(REG_OFSZ, encodeOffset(z))
    }

    /** Measure and write per-axis offsets to null sensor bias. */
    @Throws(IOException::class)
    fun calibrateOffset(targetX: Double, targetY: Double, targetZ: Double, samples: Int) {
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (i in 0 until samples) {
            val xyz = read()
            sx += xyz[0]; sy += xyz[1]; sz += xyz[2]
            try { Thread.sleep(11) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        sx /= samples; sy /= samples; sz /= samples
        setOffset(targetX - sx, targetY - sy, targetZ - sz)
    }

    /** Configure single-tap detection and enable the SINGLE_TAP interrupt. */
    @Throws(IOException::class)
    fun setTapDetection(thresholdG: Double, durationMs: Double, axes: Int = 0x07, suppress: Boolean = false) {
        writeReg(REG_THRESH_TAP, Math.round(thresholdG / 62.5e-3).toInt())
        writeReg(REG_DUR, Math.round(durationMs / 0.625).toInt())
        val tapAxes = (axes and 0x07) or (if (suppress) 0x08 else 0x00)
        writeReg(REG_TAP_AXES, tapAxes)
        enableInterrupt(INT_SINGLE_TAP)
    }

    /** Configure double-tap latency and window; enable DOUBLE_TAP interrupt. */
    @Throws(IOException::class)
    fun setDoubleTap(latencyMs: Double, windowMs: Double) {
        writeReg(REG_LATENT, Math.round(latencyMs / 1.25).toInt())
        writeReg(REG_WINDOW, Math.round(windowMs / 1.25).toInt())
        enableInterrupt(INT_DOUBLE_TAP)
    }

    /** Configure activity detection. */
    @Throws(IOException::class)
    fun setActivity(thresholdG: Double, axes: Int = 0x70, acCoupled: Boolean = true) {
        writeReg(REG_THRESH_ACT, Math.round(thresholdG / 62.5e-3).toInt())
        var aic = readReg(REG_ACT_INACT_CTL)
        aic = aic and 0xF0.inv()
        if (acCoupled) aic = aic or 0x80
        aic = aic or (axes and 0x70)
        writeReg(REG_ACT_INACT_CTL, aic)
        enableInterrupt(INT_ACTIVITY)
    }

    /** Configure inactivity detection. */
    @Throws(IOException::class)
    fun setInactivity(thresholdG: Double, timeSec: Double, axes: Int = 0x07, acCoupled: Boolean = false) {
        writeReg(REG_THRESH_INACT, Math.round(thresholdG / 62.5e-3).toInt())
        writeReg(REG_TIME_INACT, Math.round(timeSec).toInt())
        var aic = readReg(REG_ACT_INACT_CTL)
        aic = aic and 0x0F.inv()
        if (acCoupled) aic = aic or 0x08
        aic = aic or (axes and 0x07)
        writeReg(REG_ACT_INACT_CTL, aic)
        enableInterrupt(INT_INACTIVITY)
    }

    /** Configure free-fall detection and enable the FREE_FALL interrupt. */
    @Throws(IOException::class)
    fun setFreeFall(thresholdG: Double, timeMs: Double) {
        writeReg(REG_THRESH_FF, Math.round(thresholdG / 62.5e-3).toInt())
        writeReg(REG_TIME_FF, Math.round(timeMs / 5.0).toInt())
        enableInterrupt(INT_FREE_FALL)
    }

    /** Enable or disable an interrupt source and route it to INT1 or INT2. */
    @Throws(IOException::class)
    fun setInterrupt(source: Int, enabled: Boolean, pin: Int = 1) {
        var ie = readReg(REG_INT_ENABLE)
        var im = readReg(REG_INT_MAP)
        if (enabled) {
            ie = ie or source
            if (pin == 2) im = im or source else im = im and source.inv()
        } else {
            ie = ie and source.inv()
        }
        writeReg(REG_INT_ENABLE, ie)
        writeReg(REG_INT_MAP, im)
    }

    @Throws(IOException::class)
    private fun enableInterrupt(source: Int) {
        setInterrupt(source, true, 1)
    }

    /** Read the INT_SOURCE register; clears latched interrupts. */
    @Throws(IOException::class)
    fun readInterruptSource(): Int = readReg(REG_INT_SOURCE)

    /** Configure the FIFO. */
    @Throws(IOException::class)
    fun setFifoMode(mode: Int, samples: Int = 16) {
        val fifoCtl = (mode and 0xC0) or (samples and 0x1F)
        writeReg(REG_FIFO_CTL, fifoCtl)
    }

    /** Number of FIFO entries currently available (0–32). */
    @Throws(IOException::class)
    fun fifoCount(): Int = readReg(REG_FIFO_STATUS) and 0x3F

    /** Drain the FIFO, returning up to [maxSamples] (x, y, z) samples in *g*. */
    @Throws(IOException::class)
    fun readFifo(maxSamples: Int): Array<DoubleArray> {
        var n = fifoCount()
        if (n > maxSamples) n = maxSamples
        val out = arrayOfNulls<DoubleArray>(n)
        for (i in 0 until n) {
            out[i] = read()
        }
        return out as Array<DoubleArray>
    }

    /** Enter or leave sleep mode. */
    @Throws(IOException::class)
    fun setSleep(enabled: Boolean, wakeupHz: Int = 8) {
        var pwr = readReg(REG_POWER_CTL)
        if (enabled) {
            val wakeupCode = when (wakeupHz) {
                8 -> WAKEUP_8_HZ
                4 -> WAKEUP_4_HZ
                2 -> WAKEUP_2_HZ
                1 -> WAKEUP_1_HZ
                else -> return
            }
            pwr = (pwr and 0x06.inv()) or wakeupCode or 0x08
            pwr = pwr or 0x04
        } else {
            pwr = pwr and 0x04.inv()
        }
        writeReg(REG_POWER_CTL, pwr)
    }

    /** Enable or disable the activity/inactivity serial-link mode. */
    @Throws(IOException::class)
    fun setLinkMode(enabled: Boolean) {
        var pwr = readReg(REG_POWER_CTL)
        if (enabled) pwr = pwr or 0x40 else pwr = pwr and 0x40.inv()
        writeReg(REG_POWER_CTL, pwr)
    }

    /** Enable or disable auto-sleep on inactivity (requires Link=1). */
    @Throws(IOException::class)
    fun setAutoSleep(enabled: Boolean) {
        var pwr = readReg(REG_POWER_CTL)
        if (enabled) pwr = pwr or 0x20 else pwr = pwr and 0x20.inv()
        writeReg(REG_POWER_CTL, pwr)
    }

    /** Enable or disable the electrostatic self-test force on all axes. */
    @Throws(IOException::class)
    fun selfTest(enabled: Boolean) {
        var df = readReg(REG_DATA_FORMAT)
        if (enabled) df = df or 0x80 else df = df and 0x80.inv()
        writeReg(REG_DATA_FORMAT, df)
    }

    private fun encodeOffset(offsetG: Double): Int {
        var raw = Math.round(offsetG / 15.6e-3)
        if (raw >  127) raw =  127
        if (raw < -128) raw = -128
        return (raw and 0xFF).toInt()
    }

    companion object {
        const val INT_DATA_READY  = 0x80
        const val INT_SINGLE_TAP  = 0x40
        const val INT_DOUBLE_TAP  = 0x20
        const val INT_ACTIVITY    = 0x10
        const val INT_INACTIVITY  = 0x08
        const val INT_FREE_FALL   = 0x04
        const val INT_WATERMARK   = 0x02
        const val INT_OVERRUN     = 0x01

        const val FIFO_BYPASS  = 0x00
        const val FIFO_FIFO    = 0x40
        const val FIFO_STREAM  = 0x80
        const val FIFO_TRIGGER = 0xC0

        const val WAKEUP_8_HZ = 0x00
        const val WAKEUP_4_HZ = 0x02
        const val WAKEUP_2_HZ = 0x04
        const val WAKEUP_1_HZ = 0x06
    }
}