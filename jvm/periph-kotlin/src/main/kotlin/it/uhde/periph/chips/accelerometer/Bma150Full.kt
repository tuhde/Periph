package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * BMA150 full interface — extends Bma150Minimal with configuration, interrupt
 * sources, low-g / high-g / any-motion / alert logic, sleep, soft reset, and
 * self-test.
 */
open class Bma150Full @JvmOverloads constructor(conn: RegisterConnection) : Bma150Minimal(conn) {

    companion object {
        const val SOURCE_LOW_G      = 0x01
        const val SOURCE_HIGH_G     = 0x02
        const val SOURCE_ANY_MOTION = 0x04
        const val SOURCE_ALERT      = 0x08
        const val SOURCE_NEW_DATA   = 0x10

        const val STATUS_ST_RESULT    = 0x80
        const val STATUS_ALERT_PHASE  = 0x10
        const val STATUS_LG_LATCHED   = 0x08
        const val STATUS_HG_LATCHED   = 0x04
        const val STATUS_LG           = 0x02
        const val STATUS_HG           = 0x01

        private fun nearestBandwidth(bwHz: Int): Int {
            val hz = intArrayOf(25, 50, 100, 190, 375, 750, 1500)
            val bw = intArrayOf(BW_25, BW_50, BW_100, BW_190, BW_375, BW_750, BW_1500)
            var bestIdx = 2
            var bestDiff = Math.abs(hz[2] - bwHz)
            for (i in hz.indices) {
                val diff = Math.abs(hz[i] - bwHz)
                if (diff < bestDiff) {
                    bestIdx = i
                    bestDiff = diff
                }
            }
            return bw[bestIdx]
        }
    }

    private var enabledSources = 0
    private var sleeping = false

    fun setRange(rangeG: Int) {
        val rangeMask: Int
        val newRange: Int
        when (rangeG) {
            4 -> { rangeMask = RANGE_4G_MASK; newRange = 4 }
            8 -> { rangeMask = RANGE_8G_MASK; newRange = 8 }
            else -> { rangeMask = RANGE_2G_MASK; newRange = 2 }
        }
        this.rangeG = newRange
        val rb = readReg(REG_RANGE_BW)
        writeReg(REG_RANGE_BW, (rb and 0xE0) or rangeMask or (rb and 0x07))
    }

    fun setBandwidth(bandwidthHz: Int) {
        val bwCode = nearestBandwidth(bandwidthHz)
        val rb = readReg(REG_RANGE_BW)
        writeReg(REG_RANGE_BW, (rb and 0xF8) or bwCode)
    }

    fun readRaw(): IntArray {
        val raw = readBurst(REG_ACC_X_LSB, 6)
        val rx = Register.toSigned(((raw[1].toInt() and 0xFF) shl 2) or ((raw[0].toInt() and 0xC0) shr 6), 10)
        val ry = Register.toSigned(((raw[3].toInt() and 0xFF) shl 2) or ((raw[2].toInt() and 0xC0) shr 6), 10)
        val rz = Register.toSigned(((raw[5].toInt() and 0xFF) shl 2) or ((raw[4].toInt() and 0xC0) shr 6), 10)
        return intArrayOf(rx, ry, rz)
    }

    fun readTemperature(): Double {
        val raw = readReg(REG_TEMP)
        return raw * 0.5 - 30.0
    }

    fun newDataAvailable(): Boolean {
        val x = readReg(REG_ACC_X_LSB)
        val y = readReg(REG_ACC_Y_LSB)
        val z = readReg(REG_ACC_Z_LSB)
        return (x and 0x01) != 0 && (y and 0x01) != 0 && (z and 0x01) != 0
    }

    fun setShadow(enabled: Boolean) {
        val cfg = readReg(REG_CONFIG)
        writeReg(REG_CONFIG, if (enabled) cfg or 0x08 else cfg and 0x08.inv() and 0xFF)
    }

    fun setLowG(thresholdG: Double, durationMs: Int, hysteresisG: Double = 0.0, counter: Int = 0) {
        writeThreshold(REG_LG_THRES, thresholdG)
        writeReg(REG_LG_DUR, minOf(255, maxOf(0, durationMs)))
        writeHyst("lg", hysteresisG)
        writeIntCounter("lg", counter)
        enableSource(SOURCE_LOW_G)
    }

    fun setHighG(thresholdG: Double, durationMs: Int, hysteresisG: Double = 0.0, counter: Int = 0) {
        writeThreshold(REG_HG_THRES, thresholdG)
        writeReg(REG_HG_DUR, minOf(255, maxOf(0, durationMs)))
        writeHyst("hg", hysteresisG)
        writeIntCounter("hg", counter)
        enableSource(SOURCE_HIGH_G)
    }

    fun setAnyMotion(thresholdG: Double, samples: Int = 1) {
        val scale = when (rangeG) {
            4 -> FULL_SCALE_4G / 256.0
            8 -> FULL_SCALE_8G / 256.0
            else -> FULL_SCALE_2G / 256.0
        }
        val code = (thresholdG / (0.0156 * scale)).toInt().coerceIn(0, 255)
        writeReg(REG_ANY_MOTION_THRES, code)
        val durCode = when (samples) {
            3 -> 0x40
            5 -> 0x80
            7 -> 0xC0
            else -> 0x00
        }
        val hd = readReg(REG_HYST_DUR)
        writeReg(REG_HYST_DUR, (hd and 0x3F) or durCode)
        val cfg = readReg(REG_CONFIG)
        writeReg(REG_CONFIG, cfg or 0x40)
        enableSource(SOURCE_ANY_MOTION)
    }

    fun setAlert(enabled: Boolean) {
        if (enabled) {
            enabledSources = enabledSources and SOURCE_ANY_MOTION.inv()
            val cfg = readReg(REG_CONFIG)
            writeReg(REG_CONFIG, cfg or 0x40)
            enableSource(SOURCE_ALERT)
        } else {
            disableSource(SOURCE_ALERT)
        }
    }

    fun setLatch(enabled: Boolean) {
        val cfg = readReg(REG_CONFIG)
        writeReg(REG_CONFIG, if (enabled) cfg or 0x10 else cfg and 0x10.inv() and 0xFF)
    }

    fun clearInterrupt() {
        if (sleeping) return
        val ctrl = readReg(REG_CTRL)
        writeReg(REG_CTRL, ctrl or 0x40)
    }

    fun enableInterrupt(source: Int) {
        when {
            source == SOURCE_NEW_DATA -> enabledSources = enabledSources and 0x0F
            source == SOURCE_ANY_MOTION -> {
                enabledSources = enabledSources and SOURCE_NEW_DATA.inv()
                enabledSources = enabledSources and SOURCE_ALERT.inv()
            }
            source == SOURCE_ALERT -> {
                enabledSources = enabledSources and SOURCE_NEW_DATA.inv()
                enabledSources = enabledSources and SOURCE_ANY_MOTION.inv()
            }
            else -> enabledSources = enabledSources and SOURCE_NEW_DATA.inv()
        }
        enableSource(source)
    }

    fun disableInterrupt(source: Int) = disableSource(source)

    fun pollInterrupt(): Int = readReg(REG_STATUS)

    fun setWakeUp(enabled: Boolean, pauseMs: Int = 20) {
        val pauseCode = when (pauseMs) {
            80 -> 0x02
            320 -> 0x04
            2560 -> 0x06
            else -> 0x00
        }
        val cfg = readReg(REG_CONFIG)
        val out = (cfg and 0xF8) or pauseCode or (if (enabled) 0x01 else 0x00)
        writeReg(REG_CONFIG, out)
    }

    fun sleep() {
        if (sleeping) return
        val ctrl = readReg(REG_CTRL)
        writeReg(REG_CTRL, ctrl or 0x01)
        sleeping = true
    }

    fun wake() {
        if (!sleeping) return
        val ctrl = readReg(REG_CTRL)
        writeReg(REG_CTRL, ctrl and 0x01.inv() and 0xFF)
        Thread.sleep(2)
        sleeping = false
    }

    fun softReset() {
        val ctrl = readReg(REG_CTRL)
        writeReg(REG_CTRL, ctrl or 0x02)
        Thread.sleep(30)
        val rangeMask = when (rangeG) {
            4 -> RANGE_4G_MASK
            8 -> RANGE_8G_MASK
            else -> RANGE_2G_MASK
        }
        val rb = readReg(REG_RANGE_BW)
        writeReg(REG_RANGE_BW, (rb and 0xE0) or rangeMask or BW_100)
        sleeping = false
    }

    fun selfTest(): Boolean {
        val ctrl = readReg(REG_CTRL)
        writeReg(REG_CTRL, ctrl or 0x04)
        Thread.sleep(100)
        val status = readReg(REG_STATUS)
        writeReg(REG_CTRL, ctrl)
        return (status and STATUS_ST_RESULT) != 0
    }

    fun readStatus(): Int = readReg(REG_STATUS)

    fun readVersion(): IntArray {
        val raw = readReg(REG_VERSION)
        return intArrayOf((raw shr 4) and 0x0F, raw and 0x0F)
    }

    fun readCustomer(index: Int): Int =
        readReg(if (index == 0) REG_CUSTOMER_1 else REG_CUSTOMER_2)

    fun writeCustomer(index: Int, value: Int) =
        writeReg(if (index == 0) REG_CUSTOMER_1 else REG_CUSTOMER_2, value and 0xFF)

    private fun writeThreshold(reg: Int, thresholdG: Double) {
        val code = (thresholdG * 255.0 / rangeG).toInt().coerceIn(0, 255)
        writeReg(reg, code)
    }

    private fun writeHyst(kind: String, hysteresisG: Double) {
        if (hysteresisG < 0) return
        val code = (hysteresisG * 255.0 / rangeG / 32.0).toInt().coerceIn(0, 7)
        val hd = readReg(REG_HYST_DUR)
        val out = if (kind == "lg") (hd and 0xF8) or code else (hd and 0xC7) or (code shl 3)
        writeReg(REG_HYST_DUR, out)
    }

    private fun writeIntCounter(kind: String, counter: Int) {
        if (counter < 0 || counter > 3) return
        val code = (counter and 0x03) shl 4
        val ic = readReg(REG_INT_CTRL)
        val out = if (kind == "lg") (ic and 0xF3) or code else (ic and 0xCF) or (code shl 2)
        writeReg(REG_INT_CTRL, out)
    }

    private fun enableSource(source: Int) {
        if (sleeping) return
        enabledSources = enabledSources or source
        if (source == SOURCE_NEW_DATA) {
            val cfg = readReg(REG_CONFIG)
            writeReg(REG_CONFIG, cfg or 0x20)
            return
        }
        val ic = readReg(REG_INT_CTRL)
        var out = ic
        if (source == SOURCE_LOW_G)      out = out or 0x01
        if (source == SOURCE_HIGH_G)     out = out or 0x02
        if (source == SOURCE_ANY_MOTION) out = out or 0x40
        if (source == SOURCE_ALERT)     out = out or 0x80
        writeReg(REG_INT_CTRL, out)
    }

    private fun disableSource(source: Int) {
        enabledSources = enabledSources and source.inv()
        if (source == SOURCE_NEW_DATA) {
            val cfg = readReg(REG_CONFIG)
            writeReg(REG_CONFIG, cfg and 0x20.inv() and 0xFF)
            return
        }
        val ic = readReg(REG_INT_CTRL)
        var out = ic
        if (source == SOURCE_LOW_G)      out = out and 0x01.inv() and 0xFF
        if (source == SOURCE_HIGH_G)     out = out and 0x02.inv() and 0xFF
        if (source == SOURCE_ANY_MOTION) out = out and 0x40.inv() and 0xFF
        if (source == SOURCE_ALERT)     out = out and 0x80.inv() and 0xFF
        writeReg(REG_INT_CTRL, out)
    }
}
