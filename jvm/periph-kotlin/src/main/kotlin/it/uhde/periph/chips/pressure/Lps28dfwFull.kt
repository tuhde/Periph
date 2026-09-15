package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * LPS28DFW — dual full-scale digital barometer (full driver).
 *
 * Extends [Lps28dfwMinimal] with configuration, one-shot trigger, one-point
 * calibration offset, FIFO configuration / drain / level, and pressure-threshold
 * interrupt setup.
 */
class Lps28dfwFull(connection: Connection) : Lps28dfwMinimal(connection) {

    companion object {
        const val ODR_POWER_DOWN = 0x00
        const val ODR_1_HZ   = 0x01
        const val ODR_4_HZ   = 0x02
        const val ODR_10_HZ  = 0x03
        const val ODR_25_HZ  = 0x04
        const val ODR_50_HZ  = 0x05
        const val ODR_75_HZ  = 0x06
        const val ODR_100_HZ = 0x07
        const val ODR_200_HZ = 0x08

        const val AVG_4   = 0x00
        const val AVG_8   = 0x01
        const val AVG_16  = 0x02
        const val AVG_32  = 0x03
        const val AVG_64  = 0x04
        const val AVG_128 = 0x05
        const val AVG_512 = 0x07

        const val LFPF_ODR_OVER_4 = 0
        const val LFPF_ODR_OVER_9 = 1

        const val FIFO_BYPASS               = 0
        const val FIFO_FIFO                 = 1
        const val FIFO_CONTINUOUS           = 2
        const val FIFO_BYPASS_TO_FIFO       = 4
        const val FIFO_BYPASS_TO_CONTINUOUS = 5
        const val FIFO_CONTINUOUS_TO_FIFO   = 6

        const val STATUS_P_DA = 0x01
        const val STATUS_T_DA = 0x02
        const val STATUS_P_OR = 0x10
        const val STATUS_T_OR = 0x20

        private const val REG_THS_P_L      = 0x0C
        private const val REG_THS_P_H      = 0x0D
        private const val REG_FIFO_CTRL    = 0x14
        private const val REG_FIFO_WTM     = 0x15
        private const val REG_RPDS_L       = 0x1A
        private const val REG_RPDS_H       = 0x1B
        private const val REG_FIFO_STATUS1 = 0x25
        private const val REG_FIFO_DATA_XL = 0x78
    }

    /**
     * Set output data rate, averaging, full-scale mode, and IIR filter.
     */
    fun configure(odr: Int, avg: Int, fsMode: Int, lpfEn: Boolean, lpfCfg: Int) {
        this.odr = odr
        this.avg = avg
        this.fsMode = fsMode
        this.lpfEn = if (lpfEn) 1 else 0
        this.lpfCfg = lpfCfg
        val ctrl2 = (fsMode shl 6) or (lpfCfg shl 5) or (this.lpfEn shl 4) or (bdu shl 3)
        writeReg(REG_CTRL_REG2, ctrl2)
        val ctrl1 = (odr shl 3) or (avg and 0x07)
        writeReg(REG_CTRL_REG1, ctrl1)
    }

    /**
     * Burst-read pressure and temperature.
     */
    fun read(): DoubleArray {
        val b = connection.writeRead(byteArrayOf(REG_PRESS_OUT_XL.toByte()), 5)
        var p = ((b[2].toInt() and 0xFF) shl 16) or ((b[1].toInt() and 0xFF) shl 8) or (b[0].toInt() and 0xFF)
        if ((p and 0x800000) != 0) p = p or 0xFF000000.toInt()
        val t = (b[4].toInt() shl 8 or b[3].toInt()).toShort().toInt()
        val sens = if (fsMode == 0) SENSITIVITY_MODE1 else SENSITIVITY_MODE2
        return doubleArrayOf(p / sens, t / 100.0)
    }

    /** True if STATUS.P_DA is set (new pressure sample available). */
    fun isDataReady(): Boolean {
        val status = connection.writeRead(byteArrayOf(REG_STATUS.toByte()), 1)
        return (status[0].toInt() and STATUS_P_DA) != 0
    }

    /** Trigger a one-shot measurement (with ODR=0000) and read the result. */
    fun readOneshot(): DoubleArray {
        val saved = connection.writeRead(byteArrayOf(REG_CTRL_REG1.toByte()), 1)
        val savedOdr = (saved[0].toInt() and 0xFF) shr 3
        writeReg(REG_CTRL_REG1, avg and 0x07)
        val c2 = connection.writeRead(byteArrayOf(REG_CTRL_REG2.toByte()), 1)
        writeReg(REG_CTRL_REG2, (c2[0].toInt() and 0xFF) or 0x01)
        for (i in 0 until 200) {
            val status = connection.writeRead(byteArrayOf(REG_STATUS.toByte()), 1)
            if ((status[0].toInt() and STATUS_P_DA) != 0) break
            try { Thread.sleep(5) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        val result = read()
        writeReg(REG_CTRL_REG1, (savedOdr shl 3) or (avg and 0x07))
        return result
    }

    /** Program the one-point calibration offset (RPDS). */
    fun setOffset(offsetHpa: Double) {
        val sens = if (fsMode == 0) SENSITIVITY_MODE1 else SENSITIVITY_MODE2
        var raw = (offsetHpa * sens).toInt()
        if (raw < 0) raw += 0x10000
        writeReg(REG_RPDS_L, raw and 0xFF)
        writeReg(REG_RPDS_H, (raw shr 8) and 0xFF)
    }

    /** Issue a software reset and wait for the chip to reboot (~2 ms). */
    fun softreset() {
        val c2 = connection.writeRead(byteArrayOf(REG_CTRL_REG2.toByte()), 1)
        writeReg(REG_CTRL_REG2, (c2[0].toInt() and 0xFF) or 0x02)
        try { Thread.sleep(2) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    /**
     * Configure FIFO mode, watermark level, and stop-on-watermark.
     */
    fun fifoConfigure(mode: Int, wtm: Int, stopOnWtm: Boolean) {
        if (mode == FIFO_BYPASS) {
            writeReg(REG_FIFO_CTRL, 0x00)
        }
        val trig = if (mode >= 4) 1 else 0
        val fMode = mode and 0x03
        val ctrl = (trig shl 2) or ((if (stopOnWtm) 1 else 0) shl 3) or fMode
        writeReg(REG_FIFO_CTRL, ctrl)
        writeReg(REG_FIFO_WTM, wtm and 0x7F)
    }

    /**
     * Drain up to [count] pressure samples from the FIFO.
     */
    fun fifoRead(count: Int): DoubleArray {
        if (count <= 0) return DoubleArray(0)
        val n = if (count > 128) 128 else count
        val raw = connection.writeRead(byteArrayOf(REG_FIFO_DATA_XL.toByte()), n * 3)
        val sens = if (fsMode == 0) SENSITIVITY_MODE1 else SENSITIVITY_MODE2
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val b = i * 3
            var v = ((raw[b + 2].toInt() and 0xFF) shl 16) or ((raw[b + 1].toInt() and 0xFF) shl 8) or (raw[b].toInt() and 0xFF)
            if ((v and 0x800000) != 0) v = v or 0xFF000000.toInt()
            out[i] = v / sens
        }
        return out
    }

    /** Return the number of unread samples in the FIFO. */
    fun fifoLevel(): Int {
        val b = connection.writeRead(byteArrayOf(REG_FIFO_STATUS1.toByte()), 1)
        return b[0].toInt() and 0xFF
    }

    /**
     * Program the pressure threshold and enable interrupt sources.
     */
    fun setThreshold(thresholdHpa: Double, high: Boolean, low: Boolean) {
        val sens = if (fsMode == 0) 16.0 else 8.0
        var raw = (thresholdHpa * sens).toInt()
        if (raw < 0) raw = 0
        if (raw > 0x7FFF) raw = 0x7FFF
        writeReg(REG_THS_P_L, raw and 0xFF)
        writeReg(REG_THS_P_H, (raw shr 8) and 0x7F)
        val cfg = connection.writeRead(byteArrayOf(REG_INTERRUPT_CFG.toByte()), 1)
        var v = (cfg[0].toInt() and 0xFF) and 0xFFFFFFFC.toInt()
        if (high) v = v or 0x01
        if (low)  v = v or 0x02
        writeReg(REG_INTERRUPT_CFG, v)
    }

    /** Read the WHO_AM_I register. */
    fun chipId(): Int {
        val b = connection.writeRead(byteArrayOf(REG_WHO_AM_I.toByte()), 1)
        return b[0].toInt() and 0xFF
    }

    /** Compute altitude above sea level from the current pressure. */
    fun altitude(seaLevelHpa: Double = 1013.25): Double {
        val p = readPressure()
        return 44330.0 * (1.0 - Math.pow(p / seaLevelHpa, 1.0 / 5.255))
    }
}