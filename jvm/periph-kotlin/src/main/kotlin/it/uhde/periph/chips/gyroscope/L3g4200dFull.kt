package it.uhde.periph.chips.gyroscope

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * L3G4200D three-axis MEMS gyroscope — full driver.
 *
 * Extends [L3g4200dMinimal] with configuration, FIFO, high-pass filter,
 * interrupts, axis-enable, and power-mode control.
 */
open class L3g4200dFull @JvmOverloads constructor(
    connection: Connection,
    spi: Boolean = false,
) : L3g4200dMinimal(connection, spi) {
    private var odr: Int = ODR_100_HZ
    private var bw: Int = 0

    /** Read a single byte via I²C (auto-increment) or SPI (READ=1, MS=1). */
    private fun readReg(reg: Int, n: Int): ByteArray {
        if (spi) {
            connection.write(byteArrayOf(((reg or 0xC0) and 0xFF).toByte()))
            return connection.read(n)
        }
        val addr = if (n > 1) reg or 0x80 else reg
        return connection.writeRead(byteArrayOf(addr.toByte()), n)
    }

    /**
     * Configure ODR, LPF2 bandwidth, and full scale in one call.
     *
     * @param odr        ODR code 0-3 (100/200/400/800 Hz).
     * @param bandwidth  LPF2 bandwidth code 0-3.
     * @param fullScale  Full-scale in dps (250, 500, or 2000).
     */
    @JvmOverloads
    fun configure(odr: Int = ODR_100_HZ, bandwidth: Int = 0, fullScale: Int = FS_250_DPS) {
        if (fullScale != FS_250_DPS && fullScale != FS_500_DPS && fullScale != FS_2000_DPS) return
        this.odr = odr and 0x3
        this.bw = bandwidth and 0x3
        this.fullScaleDps = fullScale
        val ctrl1 = CTRL_REG1_DEFAULT or ((this.odr and 0x3) shl 6) or ((this.bw and 0x3) shl 4)
        writeReg(REG_CTRL_REG1, ctrl1)
        val fsBits = when (fullScale) {
            FS_250_DPS -> 0
            FS_500_DPS -> 1
            else -> 2
        }
        writeReg(REG_CTRL_REG4, CTRL_REG4_DEFAULT or ((fsBits and 0x3) shl 4))
    }

    /** Update the full-scale range. */
    fun setFullScale(fullScale: Int) {
        if (fullScale != FS_250_DPS && fullScale != FS_500_DPS && fullScale != FS_2000_DPS) return
        this.fullScaleDps = fullScale
        val fsBits = when (fullScale) {
            FS_250_DPS -> 0
            FS_500_DPS -> 1
            else -> 2
        }
        val ctrl4 = readReg(REG_CTRL_REG4, 1)[0].toInt() and 0xFF
        writeReg(REG_CTRL_REG4, (ctrl4 and 0xCF) or ((fsBits and 0x3) shl 4))
    }

    /** @return WHO_AM_I (0xD3 for genuine L3G4200D). */
    fun whoAmI(): Int = readReg(REG_WHO_AM_I, 1)[0].toInt() and 0xFF

    /** @return raw STATUS_REG byte. */
    fun status(): Int = readReg(REG_STATUS, 1)[0].toInt() and 0xFF

    /** @return true if STATUS_REG.ZYXDA (bit 3) is set. */
    fun dataReady(): Boolean = (status() and 0x08) != 0

    /** Read OUT_TEMP (signed 8-bit value). */
    fun temperature(): Int = readReg(REG_OUT_TEMP, 1)[0].toByte().toInt()

    /** Enter power-down mode (PD=0 in CTRL_REG1). */
    fun powerDown() {
        val ctrl1 = readReg(REG_CTRL_REG1, 1)[0].toInt() and 0xFF
        writeReg(REG_CTRL_REG1, ctrl1 and 0xF7)
    }

    /** Wake from power-down (PD=1); previously enabled axes restored. */
    fun wakeUp() {
        val ctrl1 = readReg(REG_CTRL_REG1, 1)[0].toInt() and 0xFF
        writeReg(REG_CTRL_REG1, ctrl1 or 0x08)
    }

    /** Enter sleep mode (PD=1, all axes disabled). */
    fun sleep() {
        writeReg(REG_CTRL_REG1, 0x08)
    }

    /** Enable or disable individual axes (Xen/Yen/Zen in CTRL_REG1). */
    @JvmOverloads
    fun enableAxes(x: Boolean = true, y: Boolean = true, z: Boolean = true) {
        var ctrl1 = readReg(REG_CTRL_REG1, 1)[0].toInt() and 0xFF
        ctrl1 = ctrl1 and 0xF8
        if (z) ctrl1 = ctrl1 or 0x04
        if (y) ctrl1 = ctrl1 or 0x02
        if (x) ctrl1 = ctrl1 or 0x01
        writeReg(REG_CTRL_REG1, ctrl1)
    }

    /**
     * Configure and enable the FIFO.
     *
     * @param mode      FIFO mode 0-4.
     * @param watermark Watermark threshold 0-31.
     */
    @JvmOverloads
    fun enableFifo(mode: Int = 0, watermark: Int = 0) {
        if (mode < 0 || mode > 4) return
        val wm = watermark.coerceIn(0, 31)
        val ctrl5 = readReg(REG_CTRL_REG5, 1)[0].toInt() and 0xFF
        writeReg(REG_CTRL_REG5, ctrl5 or 0x40)
        writeReg(REG_FIFO_CTRL, ((mode and 0x7) shl 5) or (wm and 0x1F))
    }

    /** Disable the FIFO. */
    fun disableFifo() {
        val ctrl5 = readReg(REG_CTRL_REG5, 1)[0].toInt() and 0xFF
        writeReg(REG_CTRL_REG5, ctrl5 and 0xBF.inv())
        writeReg(REG_FIFO_CTRL, 0x00)
    }

    /** @return FSS[4:0] from FIFO_SRC_REG. */
    fun fifoSamples(): Int = readReg(REG_FIFO_SRC, 1)[0].toInt() and 0x1F

    /**
     * Read all stored FIFO samples.
     *
     * @return List of (x, y, z) tuples in rad/s.
     */
    @JvmOverloads
    fun readFifo(maxSamples: Int = 32): List<Triple<Float, Float, Float>> {
        val n = minOf(maxSamples, fifoSamples())
        if (n == 0) return emptyList()
        val sens = sensitivity(fullScaleDps)
        val k = (Math.PI / 180.0).toFloat()
        val buf = readReg(REG_OUT_X_L, n * 6)
        val out = ArrayList<Triple<Float, Float, Float>>(n)
        for (i in 0 until n) {
            val o = i * 6
            val x = int16Le(buf, o) * sens * k
            val y = int16Le(buf, o + 2) * sens * k
            val z = int16Le(buf, o + 4) * sens * k
            out.add(Triple(x, y, z))
        }
        return out
    }

    /**
     * Enable the high-pass filter on the output path.
     */
    @JvmOverloads
    fun enableHighpass(mode: Int = 0, cutoff: Int = 0) {
        if (mode < 0 || mode > 3) return
        if (cutoff < 0 || cutoff > 9) return
        writeReg(REG_CTRL_REG2, ((mode and 0x3) shl 4) or (cutoff and 0x0F))
        val ctrl5 = readReg(REG_CTRL_REG5, 1)[0].toInt() and 0xFF
        writeReg(REG_CTRL_REG5, ctrl5 or 0x10)
    }

    /** Clear HPen in CTRL_REG5. */
    fun disableHighpass() {
        val ctrl5 = readReg(REG_CTRL_REG5, 1)[0].toInt() and 0xFF
        writeReg(REG_CTRL_REG5, ctrl5 and 0xEF)
    }

    /** Configure INT1_CFG axis/direction events. */
    @JvmOverloads
    fun setInterrupt(
        xHigh: Boolean = false, xLow: Boolean = false,
        yHigh: Boolean = false, yLow: Boolean = false,
        zHigh: Boolean = false, zLow: Boolean = false,
        andMode: Boolean = false, latch: Boolean = false,
    ) {
        var cfg = 0
        if (andMode) cfg = cfg or 0x80
        if (latch)   cfg = cfg or 0x40
        if (zHigh)   cfg = cfg or 0x20
        if (zLow)    cfg = cfg or 0x10
        if (yHigh)   cfg = cfg or 0x08
        if (yLow)    cfg = cfg or 0x04
        if (xHigh)   cfg = cfg or 0x02
        if (xLow)    cfg = cfg or 0x01
        writeReg(REG_INT1_CFG, cfg)
        if ((cfg and 0x3F) != 0) {
            val ctrl3 = readReg(REG_CTRL_REG3, 1)[0].toInt() and 0xFF
            writeReg(REG_CTRL_REG3, ctrl3 or 0x80)
        }
    }

    /**
     * Set the interrupt threshold for one axis.
     */
    fun setThreshold(axis: Char, thresholdDps: Float) {
        val raw = (thresholdDps / sensitivity(fullScaleDps)).toInt() and 0x7FFF
        val (hiReg, loReg) = when (axis) {
            'x' -> REG_INT1_THS_XH to REG_INT1_THS_XL
            'y' -> REG_INT1_THS_YH to REG_INT1_THS_YL
            'z' -> REG_INT1_THS_ZH to REG_INT1_THS_ZL
            else -> return
        }
        writeReg(hiReg, (raw shr 8) and 0x7F)
        writeReg(loReg, raw and 0xFF)
    }

    /** Set INT1_DURATION. */
    @JvmOverloads
    fun setDuration(samples: Int = 0, wait: Boolean = false) {
        if (samples !in 0..127) return
        val val_ = ((if (wait) 1 else 0) shl 7) or (samples and 0x7F)
        writeReg(REG_INT1_DURATION, val_)
    }

    /** Read INT1_SRC; reading clears the interrupt-active bit. */
    fun readIntSource(): Int = readReg(REG_INT1_SRC, 1)[0].toInt() and 0xFF

    /** Route the data-ready signal to the DRDY/INT2 pin. */
    @JvmOverloads
    fun setDataReadyPin(enable: Boolean = true) {
        val ctrl3 = readReg(REG_CTRL_REG3, 1)[0].toInt() and 0xFF
        writeReg(REG_CTRL_REG3, if (enable) ctrl3 or 0x08 else ctrl3 and 0xF7)
    }

    companion object {
        /** ODR codes (DR[1:0] in CTRL_REG1). */
        const val ODR_100_HZ = 0
        const val ODR_200_HZ = 1
        const val ODR_400_HZ = 2
        const val ODR_800_HZ = 3

        /** Full-scale ranges. */
        const val FS_250_DPS  = 250
        const val FS_500_DPS  = 500
        const val FS_2000_DPS = 2000

        /** FIFO modes (FM[2:0] in FIFO_CTRL_REG). */
        const val FIFO_BYPASS           = 0
        const val FIFO_FIFO             = 1
        const val FIFO_STREAM           = 2
        const val FIFO_STREAM_TO_FIFO   = 3
        const val FIFO_BYPASS_TO_STREAM = 4
    }
}
