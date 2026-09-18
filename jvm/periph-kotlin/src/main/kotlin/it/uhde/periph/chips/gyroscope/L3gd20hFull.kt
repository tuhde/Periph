package it.uhde.periph.chips.gyroscope

import it.uhde.periph.connection.Connection

/**
 * L3GD20H three-axis MEMS gyroscope — full driver.
 *
 * Extends [L3gd20hMinimal] with configuration, FIFO,
 * high-pass filter, interrupts, axis-enable, and power-mode control.
 */
@JvmOverloads
class L3gd20hFull @JvmOverloads constructor(
    connection: Connection,
    spi: Boolean = false
) : L3gd20hMinimal(connection, spi) {

    companion object {
        const val ODR_95_HZ  = 0
        const val ODR_190_HZ = 1
        const val ODR_380_HZ = 2
        const val ODR_760_HZ = 3

        const val FS_250_DPS  = 0
        const val FS_500_DPS  = 1
        const val FS_2000_DPS = 2

        const val FIFO_BYPASS             = 0
        const val FIFO_FIFO               = 1
        const val FIFO_STREAM             = 2
        const val FIFO_BYPASS_TO_STREAM   = 3
        const val FIFO_STREAM_TO_FIFO     = 7

        const val HPM_NORMAL      = 0
        const val HPM_REFERENCE   = 1
        const val HPM_NORMAL_ALT  = 2
        const val HPM_AUTORESET   = 3

        const val POWER_NORMAL     = "normal"
        const val POWER_SLEEP      = "sleep"
        const val POWER_POWERDOWN  = "power_down"
    }

    private var odr = ODR_95_HZ
    private var bw = 0

    /** Read a register via I²C or SPI. */
    private fun readReg(reg: Int, n: Int): ByteArray {
        return if (spi) {
            connection.write(byteArrayOf((reg or 0xC0).toByte()))
            connection.read(n)
        } else if (n > 1) {
            connection.writeRead(byteArrayOf((reg or 0x80).toByte()), n)
        } else {
            connection.writeRead(byteArrayOf(reg.toByte()), n)
        }
    }

    /**
     * Configure ODR, bandwidth, and full scale in one call.
     *
     * @param odr         ODR code 0-3 (95/190/380/760 Hz).
     * @param bw          Bandwidth code 0-3 (ODR-dependent).
     * @param fullScale   Full-scale code 0=±250, 1=±500, 2=±2000 dps.
     */
    fun configure(odr: Int, bw: Int, fullScale: Int) {
        if (odr > 3 || bw > 3 || fullScale > 2) return
        this.odr = odr
        this.bw = bw
        val fsMap = intArrayOf(250, 500, 2000)
        fullScale = fsMap[fullScale]
        val ctrl1 = CTRL_REG1_DEFAULT or ((odr and 0x3) shl 6) or ((bw and 0x3) shl 4)
        writeReg(REG_CTRL_REG1, ctrl1)
        writeReg(REG_CTRL_REG4, CTRL_REG4_DEFAULT or ((fullScale and 0x3) shl 4))
    }

    /**
     * Read raw 16-bit signed angular rate values.
     *
     * @return ShortArray {x_raw, y_raw, z_raw}.
     */
    fun gyroRaw(): ShortArray {
        val raw = readReg(REG_OUT_X_L, 6)
        return shortArrayOf(
            int16Le(raw, 0),
            int16Le(raw, 2),
            int16Le(raw, 4)
        )
    }

    /**
     * Read the relative temperature count.
     *
     * OUT_TEMP is an 8-bit signed value with 1 LSB/°C sensitivity. There is
     * no absolute calibration — it represents change from the device's
     * power-on temperature baseline. Do not convert to absolute Celsius.
     *
     * @return signed 8-bit temperature count.
     */
    fun temperature(): Int {
        return (readReg(REG_OUT_TEMP, 1)[0].toInt() and 0xFF).toByte().toInt()
    }

    /**
     * Check whether a new X/Y/Z sample is ready.
     *
     * @return true if STATUS_REG.ZYXDA (bit 3) is set.
     */
    fun dataReady(): Boolean {
        return (readReg(REG_STATUS, 1)[0].toInt() and 0x08) != 0
    }

    /**
     * Configure the high-pass filter (CTRL_REG2).
     *
     * @param mode   HPF mode 0-3 (HPM field).
     * @param cutoff HPF cutoff code 0-15 (HPCF[3:0]).
     */
    fun configureHpFilter(mode: Int, cutoff: Int) {
        if (mode > 3 || cutoff > 15) return
        writeReg(REG_CTRL_REG2, ((mode and 0x3) shl 4) or (cutoff and 0x0F))
    }

    /**
     * Enable or disable the high-pass filter on the output path.
     *
     * @param enable True to enable (sets HPen in CTRL_REG5), False to disable.
     */
    fun enableHpFilter(enable: Boolean) {
        val ctrl5 = (readReg(REG_CTRL_REG5, 1)[0].toInt() and 0xFF)
        writeReg(REG_CTRL_REG5, if (enable) ctrl5 or 0x10 else ctrl5 and 0x10.inv())
    }

    /**
     * Configure the FIFO (FIFO_CTRL_REG).
     *
     * @param mode      FIFO mode 0=Bypass, 1=FIFO, 2=Stream, 3=Bypass-to-Stream, 7=Stream-to-FIFO.
     * @param watermark Watermark threshold 0-31 (WTM[4:0]).
     */
    fun configureFifo(mode: Int, watermark: Int) {
        val valid = mode == 0 || mode == 1 || mode == 2 || mode == 3 || mode == 7
        if (!valid || watermark !in 0..31) return
        val ctrl5 = (readReg(REG_CTRL_REG5, 1)[0].toInt() and 0xFF)
        writeReg(REG_CTRL_REG5, ctrl5 or 0x40)
        writeReg(REG_FIFO_CTRL, ((mode and 0x7) shl 5) or (watermark and 0x1F))
    }

    /**
     * Enable or disable the FIFO (FIFO_EN bit in CTRL_REG5).
     *
     * @param enable True to enable FIFO, False to disable and clear to bypass.
     */
    fun enableFifo(enable: Boolean) {
        val ctrl5 = (readReg(REG_CTRL_REG5, 1)[0].toInt() and 0xFF)
        if (enable) {
            writeReg(REG_CTRL_REG5, ctrl5 or 0x40)
        } else {
            writeReg(REG_CTRL_REG5, ctrl5 and 0x40.inv())
            writeReg(REG_FIFO_CTRL, 0x00)
        }
    }

    /**
     * Read number of unread samples in FIFO (FIFO_SRC_REG FSS[4:0]).
     *
     * @return Number of stored samples (0-31).
     */
    fun fifoLevel(): Int {
        return (readReg(REG_FIFO_SRC, 1)[0].toInt() and 0x1F)
    }

    /**
     * Read all available FIFO samples and return as rad/s tuples.
     *
     * Each burst read of OUT_X_L through OUT_Z_H pops the oldest entry.
     * Reads FIFO_SRC_REG to determine sample count, then burst-reads all.
     *
     * @return List of FloatArray {x, y, z} in rad/s.
     */
    fun readFifo(): List<FloatArray> {
        val n = fifoLevel()
        if (n == 0) return emptyList()
        val sens = sensitivity(fullScale)
        val k = Math.PI.toFloat() / 180.0f
        val buf = readReg(REG_OUT_X_L, n * 6)
        val out = ArrayList<FloatArray>(n)
        for (i in 0 until n) {
            val o = i * 6
            val x = int16Le(buf, o) * sens * k
            val y = int16Le(buf, o + 2) * sens * k
            val z = int16Le(buf, o + 4) * sens * k
            out.add(floatArrayOf(x, y, z))
        }
        return out
    }

    /**
     * Set the power mode (CTRL_REG1 PD and axis enable bits).
     *
     * @param mode "normal" (PD=1, all axes on), "sleep" (PD=1, all axes off),
     *              or "power_down" (PD=0).
     */
    fun setPowerMode(mode: String) {
        val ctrl1 = (readReg(REG_CTRL_REG1, 1)[0].toInt() and 0xFF)
        when (mode) {
            POWER_NORMAL     -> writeReg(REG_CTRL_REG1, (ctrl1 and 0xF0) or 0x0F)
            POWER_SLEEP      -> writeReg(REG_CTRL_REG1, (ctrl1 and 0xF8) or 0x08)
            POWER_POWERDOWN  -> writeReg(REG_CTRL_REG1, ctrl1 and 0xF7)
        }
    }
}