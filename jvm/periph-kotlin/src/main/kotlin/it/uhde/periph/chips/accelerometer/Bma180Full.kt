package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * BMA180 — full interface — extends [Bma180Minimal] with configuration,
 * range/bandwidth/mode/filter selection, temperature, new-data, shadow,
 * sample-skip, low-g / high-g / slope / alert / tap interrupts with per-axis
 * enable and filter selection, latched or self-resetting interrupts,
 * self-wake-up, sleep, soft reset, electrostatic self-test, offset
 * regulation, version register, and the two CUSTOMER scratch bytes.
 */
class Bma180Full(connection: RegisterConnection) : Bma180Minimal(connection) {

    companion object {
        const val SOURCE_LOW_G    = 0x01
        const val SOURCE_HIGH_G   = 0x02
        const val SOURCE_SLOPE    = 0x04
        const val SOURCE_ALERT    = 0x08
        const val SOURCE_TAP      = 0x10
        const val SOURCE_NEW_DATA = 0x20

        const val STATUS_HIGH_G  = 0x80
        const val STATUS_LOW_G   = 0x40
        const val STATUS_SLOPE   = 0x20
        const val STATUS_TAP     = 0x10
        const val STATUS_X_FIRST = 0x04
        const val STATUS_Y_FIRST = 0x02
        const val STATUS_Z_FIRST = 0x01

        private const val CR3_SLOPE_ALERT  = 0x80
        private const val CR3_SLOPE_INT    = 0x40
        private const val CR3_HIGH_INT     = 0x20
        private const val CR3_LOW_INT      = 0x10
        private const val CR3_TAP_INT      = 0x08
        private const val CR3_ADV_INT      = 0x04
        private const val CR3_NEW_DATA_INT = 0x02
        private const val CR3_LAT_INT      = 0x01

        private const val HLI_HIGH_AXIS_SHIFT = 5
        private const val HLI_LOW_AXIS_SHIFT  = 1
        private const val HLI_HIGH_FILT_BIT   = 0x10
        private const val HLI_LOW_FILT_BIT    = 0x01

        private const val STI_SLOPE_AXIS_SHIFT = 5
        private const val STI_TAP_AXIS_SHIFT   = 1
        private const val STI_SLOPE_FILT_BIT   = 0x10
        private const val STI_TAP_FILT_BIT     = 0x01

        private const val HY_HIGH_SHIFT = 3
        private const val HY_LOW_MASK   = 0x07

        private const val CR4_LOW_HY_SHIFT = 6
        private const val CR4_MOT_CD_SHIFT = 4
        private const val CR4_FF_CD_SHIFT  = 2

        private const val OLSB1_RANGE_MASK = 0x0E
        private const val OLSB1_SMP_SKIP   = 0x01

        private const val GY_SHADOW    = 0x01
        private const val GZ_WAKE_UP   = 0x01

        private const val BW_HIGH_PASS_1HZ = 0x80
        private const val BW_BAND_PASS     = 0x90

        private const val TCO_X_SLOPE_MASK = 0x03
        private const val TCO_Y_WAKE_MASK  = 0x03
        private const val TCO_Z_MODE_MASK  = 0x03

        private const val GT_TAP_MASK = 0x07

        private const val LOW_DUR_MASK  = 0xFE
        private const val HIGH_DUR_MASK = 0xFE

        private const val OT_12BIT_MASK = 0x01

        private const val DUR_LSB_MS = 2.085f
    }

    private var enabledSources = 0
    private var sleeping = false

    @Throws(IOException::class)
    override fun read(): FloatArray = super.read()

    @Throws(IOException::class)
    fun setRange(rangeG: Float) {
        val bits: Int = when (rangeG) {
            1.0f -> RANGE_1G_MASK
            1.5f -> RANGE_1_5G_MASK
            2.0f -> RANGE_2G_MASK
            3.0f -> RANGE_3G_MASK
            4.0f -> RANGE_4G_MASK
            8.0f -> RANGE_8G_MASK
            16.0f -> RANGE_16G_MASK
            else -> throw IOException("rangeG must be one of 1, 1.5, 2, 3, 4, 8, 16")
        }
        this.rangeG = rangeG
        this.rangeBits = bits
        val olsb1 = readReg(REG_OFFSET_LSB1)
        writeReg(REG_OFFSET_LSB1, (olsb1 and 0xF1) or bits)
    }

    @Throws(IOException::class)
    fun setBandwidth(bandwidthHz: Int) {
        val bwCode = nearestBandwidth(bandwidthHz)
        val cur = readReg(REG_BW_TCS)
        writeReg(REG_BW_TCS, (cur and 0x0F) or bwCode)
    }

    @Throws(IOException::class)
    fun setFilterMode(mode: Int) {
        val code = when (mode) {
            1 -> BW_HIGH_PASS_1HZ
            2 -> BW_BAND_PASS
            else -> return
        }
        val cur = readReg(REG_BW_TCS)
        writeReg(REG_BW_TCS, (cur and 0x0F) or code)
    }

    @Throws(IOException::class)
    fun setMode(mode: Int) {
        require(mode in 0..3) { "mode must be 0..3" }
        val tcoz = readReg(REG_TCO_Z)
        writeReg(REG_TCO_Z, (tcoz and 0xFC) or (mode and 0x03))
    }

    @Throws(IOException::class)
    fun setResolution(bits: Int) {
        require(bits == 12 || bits == 14) { "bits must be 12 or 14" }
        val ot = readReg(REG_OFFSET_T)
        val out = if (bits == 12) ot or OT_12BIT_MASK else ot and OT_12BIT_MASK.inv() and 0xFF
        writeReg(REG_OFFSET_T, out)
    }

    @Throws(IOException::class)
    fun readRaw(): IntArray {
        val raw = connection.read(REG_ACC_X_LSB, 6)
        val rx = it.uhde.periph.connection.Register.toSigned(((raw[1].toInt() and 0xFF) shl 6) or ((raw[0].toInt() and 0xFF) shr 2), 14)
        val ry = it.uhde.periph.connection.Register.toSigned(((raw[3].toInt() and 0xFF) shl 6) or ((raw[2].toInt() and 0xFF) shr 2), 14)
        val rz = it.uhde.periph.connection.Register.toSigned(((raw[5].toInt() and 0xFF) shl 6) or ((raw[4].toInt() and 0xFF) shr 2), 14)
        return intArrayOf(rx, ry, rz)
    }

    @Throws(IOException::class)
    fun readTemperature(): Float {
        val raw = readReg(REG_TEMP)
        val signed = if (raw < 128) raw else raw - 256
        return 25.0f + (signed - 2) * 0.5f
    }

    @Throws(IOException::class)
    fun newDataAvailable(): Boolean {
        val x = readReg(REG_ACC_X_LSB)
        val y = readReg(REG_ACC_Y_LSB)
        val z = readReg(REG_ACC_Z_LSB)
        return (x and 0x01) != 0 && (y and 0x01) != 0 && (z and 0x01) != 0
    }

    @Throws(IOException::class)
    fun setShadow(enabled: Boolean) {
        val gy = readReg(REG_GAIN_Y)
        val out = if (enabled) gy and GY_SHADOW.inv() and 0xFF else gy or GY_SHADOW
        writeReg(REG_GAIN_Y, out)
    }

    @Throws(IOException::class)
    fun setSampleSkip(enabled: Boolean) {
        val olsb1 = readReg(REG_OFFSET_LSB1)
        val out = if (enabled) olsb1 or OLSB1_SMP_SKIP else olsb1 and OLSB1_SMP_SKIP.inv() and 0xFF
        writeReg(REG_OFFSET_LSB1, out)
    }

    @Throws(IOException::class)
    fun setLowG(thresholdG: Float, durationMs: Int, hysteresisG: Float,
                axes: Int, counter: Int, filtered: Boolean) {
        writeThreshold(REG_LOW_TH, thresholdG)
        writeLowDur(durationMs)
        writeLowHy(hysteresisG)
        writeLowAxes(axes)
        writeFiltBit(REG_HIGH_LOW_INFO, HLI_LOW_FILT_BIT, filtered)
        writeDebounce('l', counter)
        enableSource(SOURCE_LOW_G)
    }

    @Throws(IOException::class)
    fun setHighG(thresholdG: Float, durationMs: Int, hysteresisG: Float,
                 axes: Int, counter: Int, filtered: Boolean) {
        writeThreshold(REG_HIGH_TH, thresholdG)
        writeHighDur(durationMs)
        writeHighHy(hysteresisG)
        writeHighAxes(axes)
        writeFiltBit(REG_HIGH_LOW_INFO, HLI_HIGH_FILT_BIT, filtered)
        writeDebounce('h', counter)
        enableSource(SOURCE_HIGH_G)
    }

    @Throws(IOException::class)
    fun setSlope(thresholdG: Float, samples: Int, axes: Int, filtered: Boolean) {
        writeSlopeThreshold(REG_SLOPE_TH, thresholdG)
        writeSlopeDur(samples)
        writeSlopeAxes(axes)
        writeFiltBit(REG_SLOPE_TAPSENS, STI_SLOPE_FILT_BIT, filtered)
        writeCR3Bit(CR3_SLOPE_INT, true)
        writeCR3Bit(CR3_SLOPE_ALERT, false)
        writeCR3Bit(CR3_ADV_INT, true)
        enableSource(SOURCE_SLOPE)
    }

    @Throws(IOException::class)
    fun setAlert(enabled: Boolean) {
        if (enabled) {
            enabledSources = enabledSources and SOURCE_SLOPE.inv()
            writeCR3Bit(CR3_SLOPE_INT, false)
            writeCR3Bit(CR3_SLOPE_ALERT, true)
            writeCR3Bit(CR3_ADV_INT, true)
            enableSource(SOURCE_ALERT)
        } else {
            disableSource(SOURCE_ALERT)
            writeCR3Bit(CR3_SLOPE_ALERT, false)
        }
    }

    @Throws(IOException::class)
    fun setTap(thresholdG: Float, windowMs: Int, axes: Int, filtered: Boolean) {
        writeSlopeThreshold(REG_TAPSENS_TH, thresholdG)
        writeTapDur(windowMs)
        writeTapAxes(axes)
        writeFiltBit(REG_SLOPE_TAPSENS, STI_TAP_FILT_BIT, filtered)
        enableSource(SOURCE_TAP)
    }

    @Throws(IOException::class)
    fun setLatch(enabled: Boolean) {
        writeCR3Bit(CR3_LAT_INT, enabled)
    }

    @Throws(IOException::class)
    fun clearInterrupt() {
        if (sleeping) return
        val ctrl0 = readReg(REG_CTRL_REG0)
        writeReg(REG_CTRL_REG0, ctrl0 or CTRL_REG0_RESET_INT)
    }

    @Throws(IOException::class)
    fun enableInterrupt(source: Int) {
        if (source == SOURCE_NEW_DATA) {
            writeCR3Bit(CR3_NEW_DATA_INT, true)
        } else {
            writeCR3Bit(CR3_NEW_DATA_INT, false)
            when (source) {
                SOURCE_SLOPE -> {
                    writeCR3Bit(CR3_SLOPE_ALERT, false)
                    writeCR3Bit(CR3_SLOPE_INT, true)
                    writeCR3Bit(CR3_ADV_INT, true)
                    disableSource(SOURCE_ALERT)
                }
                SOURCE_ALERT -> {
                    writeCR3Bit(CR3_SLOPE_INT, false)
                    writeCR3Bit(CR3_SLOPE_ALERT, true)
                    writeCR3Bit(CR3_ADV_INT, true)
                    disableSource(SOURCE_SLOPE)
                }
                SOURCE_HIGH_G -> writeCR3Bit(CR3_HIGH_INT, true)
                SOURCE_LOW_G  -> writeCR3Bit(CR3_LOW_INT, true)
                SOURCE_TAP    -> writeCR3Bit(CR3_TAP_INT, true)
            }
        }
        enableSource(source)
    }

    @Throws(IOException::class)
    fun disableInterrupt(source: Int) {
        when (source) {
            SOURCE_NEW_DATA -> writeCR3Bit(CR3_NEW_DATA_INT, false)
            SOURCE_SLOPE    -> writeCR3Bit(CR3_SLOPE_INT, false)
            SOURCE_ALERT -> {
                writeCR3Bit(CR3_SLOPE_ALERT, false)
                writeCR3Bit(CR3_ADV_INT, false)
            }
            SOURCE_HIGH_G -> writeCR3Bit(CR3_HIGH_INT, false)
            SOURCE_LOW_G  -> writeCR3Bit(CR3_LOW_INT, false)
            SOURCE_TAP    -> writeCR3Bit(CR3_TAP_INT, false)
        }
        disableSource(source)
    }

    @Throws(IOException::class)
    fun pollInterrupt(): Int = readReg(REG_STATUS_REG3)

    @Throws(IOException::class)
    fun readStatus(): IntArray = intArrayOf(
        readReg(REG_STATUS_REG1), readReg(REG_STATUS_REG2),
        readReg(REG_STATUS_REG3), readReg(REG_STATUS_REG4),
    )

    @Throws(IOException::class)
    fun setWakeUp(enabled: Boolean, pauseMs: Int) {
        val code = when (pauseMs) {
            80 -> 0x01
            320 -> 0x02
            2560 -> 0x03
            else -> 0x00
        }
        val tcoy = readReg(REG_TCO_Y)
        writeReg(REG_TCO_Y, (tcoy and 0xFC) or code)
        val gz = readReg(REG_GAIN_Z)
        val out = if (enabled) gz or GZ_WAKE_UP else gz and GZ_WAKE_UP.inv() and 0xFF
        writeReg(REG_GAIN_Z, out)
    }

    @Throws(IOException::class)
    fun sleep() {
        if (sleeping) return
        val ctrl0 = readReg(REG_CTRL_REG0)
        writeReg(REG_CTRL_REG0, ctrl0 or CTRL_REG0_SLEEP)
        sleeping = true
    }

    @Throws(IOException::class, InterruptedException::class)
    fun wake() {
        if (!sleeping) return
        val ctrl0 = readReg(REG_CTRL_REG0)
        writeReg(REG_CTRL_REG0, ctrl0 and CTRL_REG0_SLEEP.inv() and 0xFF)
        Thread.sleep(2)
        sleeping = false
    }

    @Throws(IOException::class, InterruptedException::class)
    fun softReset() {
        writeReg(REG_RESET, SOFT_RESET_CMD)
        Thread.sleep(30)
        this.rangeG = 2.0f
        this.rangeBits = RANGE_2G_MASK
        val id = readReg(REG_CHIP_ID)
        if ((id and CHIP_ID_MASK) != CHIP_ID_VALUE) {
            throw IOException(String.format(
                "BMA180 CHIP_ID after reset: expected 0x%02X, got 0x%02X",
                CHIP_ID_VALUE, id and CHIP_ID_MASK))
        }
        val ctrl0 = readReg(REG_CTRL_REG0)
        writeReg(REG_CTRL_REG0, ctrl0 or CTRL_REG0_EE_W)
        val olsb1 = readReg(REG_OFFSET_LSB1)
        writeReg(REG_OFFSET_LSB1, (olsb1 and 0xF1) or RANGE_2G_MASK)
        val bw = readReg(REG_BW_TCS)
        writeReg(REG_BW_TCS, (bw and 0x0F) or 0x40)
        sleeping = false
    }

    @Throws(IOException::class, InterruptedException::class)
    fun selfTest(): Boolean {
        val ctrl0 = readReg(REG_CTRL_REG0)
        writeReg(REG_CTRL_REG0, ctrl0 or CTRL_REG0_ST0)
        Thread.sleep(10)
        val r = readRaw()
        writeReg(REG_CTRL_REG0, ctrl0)
        val ok = Math.abs(r[0]) > 200 && Math.abs(r[1]) > 200 && Math.abs(r[2]) > 200
        softReset()
        return ok
    }

    @Throws(IOException::class, InterruptedException::class)
    fun calibrateOffset(axes: Int, mode: Int) {
        require(mode in 0..3) { "mode must be 0..3" }
        var cr4 = readReg(REG_CTRL_REG4)
        writeReg(REG_CTRL_REG4, (cr4 and 0xFC) or (mode and 0x03))
        val ax = arrayOf(
            intArrayOf(0x01, 0x80), intArrayOf(0x02, 0x40), intArrayOf(0x04, 0x20)
        )
        for (a in ax) {
            if ((axes and a[0]) == 0) continue
            var ctrl1 = readReg(REG_CTRL_REG1)
            writeReg(REG_CTRL_REG1, ctrl1 or a[1])
            for (i in 0 until 100) {
                val s1 = readReg(REG_STATUS_REG1)
                if ((s1 and 0x02) != 0) break
                Thread.sleep(100)
            }
            ctrl1 = readReg(REG_CTRL_REG1)
            writeReg(REG_CTRL_REG1, ctrl1 and a[1].inv() and 0xFF)
        }
        cr4 = readReg(REG_CTRL_REG4)
        writeReg(REG_CTRL_REG4, cr4 and 0xFC)
    }

    @Throws(IOException::class)
    fun readVersion(): IntArray {
        val raw = readReg(REG_VERSION)
        return intArrayOf((raw shr 4) and 0x0F, raw and 0x0F)
    }

    @Throws(IOException::class)
    fun readCustomer(index: Int): Int = readReg(if (index == 0) REG_CD1 else REG_CD2)

    @Throws(IOException::class)
    fun writeCustomer(index: Int, value: Int) {
        writeReg(if (index == 0) REG_CD1 else REG_CD2, value and 0xFF)
    }

    @Throws(IOException::class)
    private fun writeThreshold(reg: Int, thresholdG: Float) {
        var code = Math.round(thresholdG / rangeG * 255.0f)
        if (code < 0) code = 0
        if (code > 255) code = 255
        writeReg(reg, code)
    }

    @Throws(IOException::class)
    private fun writeSlopeThreshold(reg: Int, thresholdG: Float) {
        var code = Math.round(thresholdG / (0.0156f * rangeG / 2.0f))
        if (code < 0) code = 0
        if (code > 255) code = 255
        writeReg(reg, code)
    }

    @Throws(IOException::class)
    private fun writeLowDur(durationMs: Int) {
        var code = Math.round(durationMs / DUR_LSB_MS)
        if (code < 0) code = 0
        if (code > 127) code = 127
        val ld = readReg(REG_LOW_DUR)
        writeReg(REG_LOW_DUR, (ld and 0x01) or ((code and 0x7F) shl 1))
    }

    @Throws(IOException::class)
    private fun writeHighDur(durationMs: Int) {
        var code = Math.round(durationMs / DUR_LSB_MS)
        if (code < 0) code = 0
        if (code > 127) code = 127
        val hd = readReg(REG_HIGH_DUR)
        writeReg(REG_HIGH_DUR, (hd and 0x01) or ((code and 0x7F) shl 1))
    }

    @Throws(IOException::class)
    private fun writeLowHy(hysteresisG: Float) {
        var code = Math.round(hysteresisG / rangeG * 255.0f / 32.0f)
        if (code < 0) code = 0
        if (code > 31) code = 31
        val hy = readReg(REG_HY)
        writeReg(REG_HY, (hy and 0xF8) or (code and HY_LOW_MASK))
        val cr4 = readReg(REG_CTRL_REG4)
        writeReg(REG_CTRL_REG4, (cr4 and (0x03 shl CR4_LOW_HY_SHIFT).inv()) or (((code shr 3) and 0x03) shl CR4_LOW_HY_SHIFT))
    }

    @Throws(IOException::class)
    private fun writeHighHy(hysteresisG: Float) {
        var code = Math.round(hysteresisG / rangeG * 255.0f / 32.0f)
        if (code < 0) code = 0
        if (code > 31) code = 31
        val hy = readReg(REG_HY)
        writeReg(REG_HY, (hy and 0x07) or ((code and 0x1F) shl HY_HIGH_SHIFT))
    }

    @Throws(IOException::class)
    private fun writeLowAxes(axes: Int) {
        val hli = readReg(REG_HIGH_LOW_INFO)
        writeReg(REG_HIGH_LOW_INFO, (hli and 0xF1) or ((axes and 0x07) shl HLI_LOW_AXIS_SHIFT))
    }

    @Throws(IOException::class)
    private fun writeHighAxes(axes: Int) {
        val hli = readReg(REG_HIGH_LOW_INFO)
        writeReg(REG_HIGH_LOW_INFO, (hli and 0x0F) or ((axes and 0x07) shl HLI_HIGH_AXIS_SHIFT))
    }

    @Throws(IOException::class)
    private fun writeSlopeAxes(axes: Int) {
        val st = readReg(REG_SLOPE_TAPSENS)
        writeReg(REG_SLOPE_TAPSENS, (st and 0x0F) or ((axes and 0x07) shl STI_SLOPE_AXIS_SHIFT))
    }

    @Throws(IOException::class)
    private fun writeTapAxes(axes: Int) {
        val st = readReg(REG_SLOPE_TAPSENS)
        writeReg(REG_SLOPE_TAPSENS, (st and 0xF1) or ((axes and 0x07) shl STI_TAP_AXIS_SHIFT))
    }

    @Throws(IOException::class)
    private fun writeFiltBit(reg: Int, bit: Int, enabled: Boolean) {
        var v = readReg(reg)
        if (enabled) v = v or bit else v = v and bit.inv() and 0xFF
        writeReg(reg, v)
    }

    @Throws(IOException::class)
    private fun writeDebounce(kind: Char, counter: Int) {
        if (counter > 3) throw IOException("counter must be 0..3")
        val code = (counter and 0x03) shl 2
        val cr4 = readReg(REG_CTRL_REG4)
        if (kind == 'l') {
            writeReg(REG_CTRL_REG4, (cr4 and (0x03 shl CR4_FF_CD_SHIFT).inv()) or (code and (0x03 shl CR4_FF_CD_SHIFT)))
        } else {
            writeReg(REG_CTRL_REG4, (cr4 and (0x03 shl CR4_MOT_CD_SHIFT).inv()) or ((code shl 2) and (0x03 shl CR4_MOT_CD_SHIFT)))
        }
    }

    @Throws(IOException::class)
    private fun writeSlopeDur(samples: Int) {
        val code = when (samples) {
            3 -> 0x01
            5 -> 0x02
            7 -> 0x03
            else -> 0x00
        }
        val tcox = readReg(REG_TCO_X)
        writeReg(REG_TCO_X, (tcox and 0xFC) or code)
    }

    @Throws(IOException::class)
    private fun writeTapDur(windowMs: Int) {
        val code = nearestTapDur(windowMs)
        val gt = readReg(REG_GAIN_T)
        writeReg(REG_GAIN_T, (gt and 0xF8) or code)
    }

    @Throws(IOException::class)
    private fun writeCR3Bit(bit: Int, enabled: Boolean) {
        if (sleeping) return
        val cr3 = readReg(REG_CTRL_REG3)
        val out = if (enabled) cr3 or bit else cr3 and bit.inv() and 0xFF
        writeReg(REG_CTRL_REG3, out)
    }

    private fun enableSource(source: Int) {
        if (sleeping) return
        enabledSources = enabledSources or source
    }

    private fun disableSource(source: Int) {
        enabledSources = enabledSources and source.inv() and 0xFF
    }

    private fun nearestBandwidth(bandwidthHz: Int): Int {
        val hz = intArrayOf(10, 20, 40, 75, 150, 300, 600, 1200)
        val codes = intArrayOf(0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70)
        var best = codes[3]
        var bestDiff = Int.MAX_VALUE
        for (i in hz.indices) {
            val d = Math.abs(hz[i] - bandwidthHz)
            if (d < bestDiff) {
                bestDiff = d
                best = codes[i]
            }
        }
        return best
    }

    private fun nearestTapDur(windowMs: Int): Int {
        val ms = intArrayOf(50, 75, 100, 150, 250, 500, 750, 1000)
        val codes = intArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        var best = codes[4]
        var bestDiff = Int.MAX_VALUE
        for (i in ms.indices) {
            if (ms[i] >= windowMs) {
                val d = ms[i] - windowMs
                if (d < bestDiff) {
                    bestDiff = d
                    best = codes[i]
                }
            }
        }
        if (bestDiff == Int.MAX_VALUE) {
            bestDiff = Int.MAX_VALUE
            for (i in ms.indices.reversed()) {
                val d = windowMs - ms[i]
                if (d < bestDiff) {
                    bestDiff = d
                    best = codes[i]
                }
            }
        }
        return best
    }
}