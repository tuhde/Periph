package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * ADXL362 — 3-axis MEMS accelerometer (Analog Devices) — full interface (SPI).
 *
 * Extends [Adxl362Minimal] with the chip's full API: device-ID triple read,
 * soft-reset, range/ODR/antialiasing/noise configuration, wake-up mode,
 * external clock and external sample sync, 8-bit low-resolution reads,
 * on-chip temperature, STATUS accessors, 512-sample FIFO configuration
 * and draining, activity/inactivity thresholds and timers (referenced or
 * absolute, default/linked/loop modes), per-pin (INT1/INT2) source mapping and
 * active-high/active-low polarity, and self-test.
 */
class Adxl362Full @JvmOverloads constructor(connection: Connection) : Adxl362Minimal(connection) {

    /** Soft-reset the chip (writes 0x52 to SOFT_RESET, waits ≥0.5 ms). */
    @Throws(IOException::class)
    fun softReset() {
        writeReg(REG_SOFT_RESET, SOFT_RESET_KEY)
        sleepMs(1)
        rangeBits = 0x00
        odrHz = 100.0f
    }

    /** Return raw device-ID bytes (DEVID_AD, DEVID_MST, PARTID, REVID). */
    @Throws(IOException::class)
    fun deviceId(): IntArray {
        val ids = readBurst(REG_DEVID_AD, 4)
        return intArrayOf(
            ids[0].toInt() and 0xFF, ids[1].toInt() and 0xFF,
            ids[2].toInt() and 0xFF, ids[3].toInt() and 0xFF,
        )
    }

    /** Set the measurement range to ±2/±4/±8 g. */
    @Throws(IOException::class)
    fun setRange(rangeG: Int) {
        val code: Int = when (rangeG) {
            2 -> 0x00
            4 -> 0x40
            8 -> 0x80
            else -> return
        }
        val f = readReg(REG_FILTER_CTL)
        writeReg(REG_FILTER_CTL, (f and 0x3F) or (code and 0xC0))
        rangeBits = code
        if (odrHz > 0) sleepMs((1000.0f / odrHz + 1).toInt())
    }

    /** Set the output data rate to the nearest supported value (12.5–400 Hz). */
    @Throws(IOException::class)
    fun setOdr(odrHz: Float) {
        val codes = intArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        val rates = floatArrayOf(12.5f, 25.0f, 50.0f, 100.0f, 200.0f, 400.0f, 400.0f, 400.0f)
        var bestCode = codes[0]
        var bestRate = rates[0]
        var bestDiff = Math.abs(bestRate - odrHz)
        for (i in 1 until codes.size) {
            val d = Math.abs(rates[i] - odrHz)
            if (d < bestDiff) { bestCode = codes[i]; bestRate = rates[i]; bestDiff = d }
        }
        val f = readReg(REG_FILTER_CTL)
        writeReg(REG_FILTER_CTL, (f and 0xF8) or (bestCode and 0x07))
        this.odrHz = bestRate
    }

    /** Set FILTER_CTL.HALF_BW (antialiasing bandwidth = ODR/4 or ODR/2). */
    @Throws(IOException::class)
    fun setHalfBandwidth(enabled: Boolean) {
        val f = readReg(REG_FILTER_CTL)
        writeReg(REG_FILTER_CTL, if (enabled) f or 0x10 else f and 0x10.inv())
    }

    /** Set POWER_CTL.LOW_NOISE (0=normal, 1=low, 2=ultralow noise). */
    @Throws(IOException::class)
    fun setNoiseMode(mode: Int) {
        val p = readReg(REG_POWER_CTL)
        writeReg(REG_POWER_CTL, (p and 0xCF) or ((mode shl 4) and 0x30))
    }

    /** Set POWER_CTL.WAKEUP (270 nA idle mode). */
    @Throws(IOException::class)
    fun setWakeupMode(enabled: Boolean) {
        val p = readReg(REG_POWER_CTL)
        writeReg(REG_POWER_CTL, if (enabled) p or 0x08 else p and 0x08.inv())
    }

    /** Set POWER_CTL.AUTOSLEEP; effective only in linked/loop mode. */
    @Throws(IOException::class)
    fun setAutosleep(enabled: Boolean) {
        val p = readReg(REG_POWER_CTL)
        writeReg(REG_POWER_CTL, if (enabled) p or 0x04 else p and 0x04.inv())
    }

    /** Set POWER_CTL.EXT_CLK; INT1 is repurposed as clock input. */
    @Throws(IOException::class)
    fun setExternalClock(enabled: Boolean) {
        val p = readReg(REG_POWER_CTL)
        writeReg(REG_POWER_CTL, if (enabled) p or 0x40 else p and 0x40.inv())
    }

    /** Set FILTER_CTL.EXT_SAMPLE; INT2 is repurposed as sync trigger input. */
    @Throws(IOException::class)
    fun setExternalSampleTrigger(enabled: Boolean) {
        val f = readReg(REG_FILTER_CTL)
        writeReg(REG_FILTER_CTL, if (enabled) f or 0x08 else f and 0x08.inv())
    }

    /**
     * Read 3-axis acceleration using the 8-bit XDATA/YDATA/ZDATA registers.
     * @return (x, y, z) acceleration in *g*, ~16-LSB resolution.
     */
    @Throws(IOException::class)
    fun read8bit(): DoubleArray {
        val raw = readBurst(REG_XDATA, 3)
        val sx = if ((raw[0].toInt() and 0x80) != 0) ((raw[0].toInt() and 0xFF) - 256) else (raw[0].toInt() and 0xFF)
        val sy = if ((raw[1].toInt() and 0x80) != 0) ((raw[1].toInt() and 0xFF) - 256) else (raw[1].toInt() and 0xFF)
        val sz = if ((raw[2].toInt() and 0x80) != 0) ((raw[2].toInt() and 0xFF) - 256) else (raw[2].toInt() and 0xFF)
        val sens = sensitivity() * 16.0f
        return doubleArrayOf(sx * sens, sy * sens, sz * sens)
    }

    /** Read the on-chip temperature sensor (typical bias/sensitivity). */
    @Throws(IOException::class)
    fun temperature(): Double {
        val raw = readBurst(REG_TEMP_L, 2)
        val raw12 = signExtend12(((raw[1].toInt() and 0x0F) shl 8) or (raw[0].toInt() and 0xFF))
        return 25.0 + (raw12 - 350) * 0.065
    }

    /** Read the raw STATUS register byte. */
    @Throws(IOException::class)
    fun status(): Int = readReg(REG_STATUS)

    /** Return STATUS.AWAKE. */
    @Throws(IOException::class)
    fun awake(): Boolean = (status() and STATUS_AWAKE) != 0

    /** Return STATUS.DATA_READY. */
    @Throws(IOException::class)
    fun dataReady(): Boolean = (status() and STATUS_DATA_READY) != 0

    /** Return the 10-bit FIFO entry count (0–512). */
    @Throws(IOException::class)
    fun fifoEntries(): Int = readFifoEntries()

    /** Configure the FIFO mode, optional temperature storage, and watermark. */
    @Throws(IOException::class)
    fun configureFifo(mode: Int, storeTemp: Boolean, watermark: Int) {
        require(mode in 0..3) { "mode must be 0..3" }
        require(watermark in 0..0x1FF) { "watermark must be 0..511" }
        val fc = (mode and 0x03) or (((watermark shr 8) and 0x01) shl 3) or (if (storeTemp) 0x04 else 0x00)
        writeReg(REG_FIFO_CONTROL, fc)
        writeReg(REG_FIFO_SAMPLES, watermark and 0xFF)
    }

    /**
     * Read all available FIFO entries. Each row is [axis, value]; axis is
     * one of AXIS_X/AXIS_Y/AXIS_Z/AXIS_TEMP and value is in *g* (axes 0–2)
     * or °C (axis 3).
     */
    @Throws(IOException::class)
    fun readFifo(): Array<DoubleArray> {
        val n = fifoEntries()
        if (n == 0) return arrayOf()
        val raw = readFifo(n * 2)
        val sens = sensitivity().toDouble()
        val out = Array(n) { doubleArrayOf(0.0, 0.0) }
        for (i in 0 until n) {
            val lo = raw[2 * i].toInt() and 0xFF
            val hi = raw[2 * i + 1].toInt() and 0xFF
            val raw16 = (hi shl 8) or lo
            val axis = (raw16 shr 14) and 0x03
            val raw12 = signExtend12(raw16 and 0x0FFF)
            out[i][0] = axis.toDouble()
            out[i][1] = if (axis == AXIS_TEMP) 25.0 + (raw12 - 350) * 0.065 else raw12 * sens
        }
        return out
    }

    /** Set the activity threshold in *g* (clamped to 10-bit range). */
    @Throws(IOException::class)
    fun setActivityThreshold(thresholdG: Double, referenced: Boolean = false) {
        var raw = Math.round(thresholdG / sensitivity().toDouble()).toInt()
        if (raw < 0) raw = 0
        if (raw > 0x3FF) raw = 0x3FF
        writeReg(REG_THRESH_ACT_L, raw and 0xFF)
        writeReg(REG_THRESH_ACT_H, (raw shr 8) and 0x03)
        val aic = readReg(REG_ACT_INACT_CTL)
        writeReg(REG_ACT_INACT_CTL, if (referenced) aic or 0x02 else aic and 0x02.inv())
    }

    /** Set the activity-time filter (0–255 samples). */
    @Throws(IOException::class)
    fun setActivityTime(samples: Int) {
        writeReg(REG_TIME_ACT, samples and 0xFF)
    }

    /** Set the inactivity threshold in *g* (clamped to 10-bit range). */
    @Throws(IOException::class)
    fun setInactivityThreshold(thresholdG: Double, referenced: Boolean = false) {
        var raw = Math.round(thresholdG / sensitivity().toDouble()).toInt()
        if (raw < 0) raw = 0
        if (raw > 0x3FF) raw = 0x3FF
        writeReg(REG_THRESH_INACT_L, raw and 0xFF)
        writeReg(REG_THRESH_INACT_H, (raw shr 8) and 0x03)
        val aic = readReg(REG_ACT_INACT_CTL)
        writeReg(REG_ACT_INACT_CTL, if (referenced) aic or 0x08 else aic and 0x08.inv())
    }

    /** Set the inactivity-time filter (0–65535 samples). */
    @Throws(IOException::class)
    fun setInactivityTime(samples: Int) {
        writeReg(REG_TIME_INACT_L, samples and 0xFF)
        writeReg(REG_TIME_INACT_H, (samples shr 8) and 0xFF)
    }

    /** Set ACT_INACT_CTL.ACT_EN. */
    @Throws(IOException::class)
    fun enableActivityDetection(enabled: Boolean) {
        val aic = readReg(REG_ACT_INACT_CTL)
        writeReg(REG_ACT_INACT_CTL, if (enabled) aic or 0x01 else aic and 0x01.inv())
    }

    /** Set ACT_INACT_CTL.INACT_EN. */
    @Throws(IOException::class)
    fun enableInactivityDetection(enabled: Boolean) {
        val aic = readReg(REG_ACT_INACT_CTL)
        writeReg(REG_ACT_INACT_CTL, if (enabled) aic or 0x04 else aic and 0x04.inv())
    }

    /** Set ACT_INACT_CTL.LINKLOOP (0=default, 1=linked, 3=loop). */
    @Throws(IOException::class)
    fun setLinkLoopMode(mode: Int) {
        require(mode == LINKLOOP_DEFAULT || mode == LINKLOOP_LINKED || mode == LINKLOOP_LOOP) {
            "mode must be 0 (default), 1 (linked), or 3 (loop)"
        }
        val aic = readReg(REG_ACT_INACT_CTL)
        writeReg(REG_ACT_INACT_CTL, (aic and 0xCF) or ((mode shl 4) and 0x30))
    }

    /** Map one interrupt source to the named INT pin (1 or 2). */
    @Throws(IOException::class)
    fun setInterrupt(pin: Int, source: Int, enabled: Boolean) {
        require(pin == 1 || pin == 2) { "pin must be 1 or 2" }
        val reg = if (pin == 1) REG_INTMAP1 else REG_INTMAP2
        val cur = readReg(reg)
        val bit = intmapBit(source)
        writeReg(reg, if (enabled) cur or bit else cur and bit.inv())
    }

    /** Set the active-low polarity for one INT pin. */
    @Throws(IOException::class)
    fun setInterruptPolarity(pin: Int, activeLow: Boolean) {
        require(pin == 1 || pin == 2) { "pin must be 1 or 2" }
        val reg = if (pin == 1) REG_INTMAP1 else REG_INTMAP2
        val cur = readReg(reg)
        writeReg(reg, if (activeLow) cur or INTMAP_INT_LOW else cur and INTMAP_INT_LOW.inv())
    }

    /** Enable or disable the electrostatic self-test force on all axes. */
    @Throws(IOException::class)
    fun selfTest(enabled: Boolean) {
        val st = readReg(REG_SELF_TEST)
        writeReg(REG_SELF_TEST, if (enabled) st or 0x01 else st and 0x01.inv())
        if (enabled && odrHz > 0) sleepMs((4000.0f / odrHz + 1).toInt())
    }

    companion object {
        // Interrupt source constants (used by [setInterrupt]).
        const val SOURCE_DATA_READY    = 0
        const val SOURCE_FIFO_READY    = 1
        const val SOURCE_FIFO_WATERMARK = 2
        const val SOURCE_FIFO_OVERRUN  = 3
        const val SOURCE_ACT           = 4
        const val SOURCE_INACT         = 5
        const val SOURCE_AWAKE         = 6

        // Noise mode constants (POWER_CTL.LOW_NOISE[5:4]).
        const val NOISE_NORMAL   = 0
        const val NOISE_LOW      = 1
        const val NOISE_ULTRALOW = 2

        // Link/loop mode constants (ACT_INACT_CTL.LINKLOOP[5:4]).
        const val LINKLOOP_DEFAULT = 0
        const val LINKLOOP_LINKED  = 1
        const val LINKLOOP_LOOP    = 3

        // FIFO mode constants (FIFO_CONTROL.FIFO_MODE[1:0]).
        const val FIFO_DISABLED     = 0
        const val FIFO_OLDEST_SAVED = 1
        const val FIFO_STREAM       = 2
        const val FIFO_TRIGGERED    = 3

        // FIFO entry-axis constants (top 2 bits of each 16-bit FIFO entry).
        const val AXIS_X    = 0
        const val AXIS_Y    = 1
        const val AXIS_Z    = 2
        const val AXIS_TEMP = 3

        // INTMAP bit layout.
        private const val INTMAP_DATA_READY    = 0x01
        private const val INTMAP_FIFO_READY    = 0x02
        private const val INTMAP_FIFO_WATERMARK = 0x04
        private const val INTMAP_FIFO_OVERRUN  = 0x08
        private const val INTMAP_ACT           = 0x10
        private const val INTMAP_INACT         = 0x20
        private const val INTMAP_AWAKE         = 0x40
        private const val INTMAP_INT_LOW       = 0x80

        private fun intmapBit(source: Int): Int = when (source) {
            SOURCE_DATA_READY -> INTMAP_DATA_READY
            SOURCE_FIFO_READY -> INTMAP_FIFO_READY
            SOURCE_FIFO_WATERMARK -> INTMAP_FIFO_WATERMARK
            SOURCE_FIFO_OVERRUN -> INTMAP_FIFO_OVERRUN
            SOURCE_ACT -> INTMAP_ACT
            SOURCE_INACT -> INTMAP_INACT
            SOURCE_AWAKE -> INTMAP_AWAKE
            else -> throw IllegalArgumentException("invalid source: $source")
        }
    }
}