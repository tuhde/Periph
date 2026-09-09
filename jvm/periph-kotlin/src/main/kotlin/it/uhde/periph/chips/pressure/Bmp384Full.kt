package it.uhde.periph.chips.pressure

import java.io.IOException

/**
 * BMP384 — full driver. Extends [Bmp384Minimal] with oversampling/IIR/ODR
 * configuration, power-mode switching, data-ready polling, soft reset, and
 * FIFO support.
 *
 * Mode constants: [MODE_SLEEP], [MODE_FORCED], [MODE_NORMAL]
 */
class Bmp384Full @JvmOverloads constructor(
    conn: Connection,
    addr: Int = 0x76
) : Bmp384Minimal(conn, addr) {

    /** One parsed FIFO frame. */
    data class FifoFrame(val type: String, val value: Double)

    /**
     * Write OSR, CONFIG, and ODR registers.
     *
     * @param osrP      pressure oversampling (0–5)
     * @param osrT      temperature oversampling (0–5)
     * @param iirFilter IIR filter coefficient index (0–7)
     * @param odrSel    output data rate selector (0x00–0x11)
     */
    fun configure(osrP: Int, osrT: Int, iirFilter: Int, odrSel: Int) {
        this.osrP = osrP
        this.osrT = osrT
        this.iir   = iirFilter
        this.odr   = odrSel
        writeReg(REG_OSR,    (osrT shl 3) or (osrP shl 0))
        writeReg(REG_CONFIG, (iirFilter shl 1))
        writeReg(REG_ODR,    odrSel)
    }

    /** Read both pressure and temperature in a single burst. */
    fun read(): DoubleArray {
        if (mode == MODE_FORCED) triggerForced()
        val burst = readBurst()
        val t = compensateTemperature(burst[1])
        val p = compensatePressure(burst[0]) / 100.0
        return doubleArrayOf(p, t)
    }

    /** Trigger a forced measurement, wait T_conv, then return both values. */
    fun readForced(): DoubleArray {
        val prevMode = this.mode
        try {
            setMode(MODE_FORCED)
            triggerForced()
            val tConvMs = computeTConvMs(osrP, osrT)
            Thread.sleep(tConvMs.toLong())
            val burst = readBurst()
            val t = compensateTemperature(burst[1])
            val p = compensatePressure(burst[0]) / 100.0
            return doubleArrayOf(p, t)
        } finally {
            this.mode = prevMode
            applyPwr()
        }
    }

    /** Set the power mode. */
    fun setMode(mode: Int) {
        this.mode = mode
        applyPwr()
    }

    /** True if STATUS.drdy_press is set. */
    fun isDataReady(): Boolean = (readReg(REG_STATUS) and (1 shl 5)) != 0

    /** Soft-reset, re-read calibration, re-apply configuration. */
    fun softreset() {
        writeReg(REG_CMD, SOFT_RESET_CMD)
        Thread.sleep(3)
        readCalibration()
        applyConfig()
    }

    /** Configure FIFO source, watermark, and stop-on-full behaviour. */
    fun fifoConfigure(pressEn: Boolean, tempEn: Boolean, wtm: Int, stopOnFull: Boolean = false) {
        val cfg1 = (1 shl 4) or
            ((if (stopOnFull) 1 else 0) shl 3) or
            ((if (tempEn) 1 else 0) shl 1) or
            (if (pressEn) 1 else 0)
        writeReg(0x17, cfg1)
        writeReg(0x15, wtm and 0xFF)
        writeReg(0x16, (wtm shr 8) and 0x01)
    }

    /** Read and parse every available FIFO frame. */
    fun fifoRead(): List<FifoFrame> {
        val lenLo = readReg(0x12)
        val lenHi = readReg(0x13)
        val length = ((lenHi and 0xFF) shl 8) or (lenLo and 0xFF)
        if (length == 0) return emptyList()
        val buf = connection.writeRead(byteArrayOf(0x14.toByte()), length)

        val frames = mutableListOf<FifoFrame>()
        var i = 0
        while (i < buf.size) {
            val hdr = buf[i].toInt() and 0xFF
            if (hdr == 0x84) {  // Pressure
                if (i + 3 >= buf.size) break
                val uncomp = ((buf[i + 3].toInt() and 0xFF) shl 16) or
                             ((buf[i + 2].toInt() and 0xFF) shl 8) or
                             (buf[i + 1].toInt() and 0xFF)
                val vPa = compensatePressureWithTLin(uncomp, tLin)
                frames.add(FifoFrame("pressure", vPa / 100.0))
                i += 4
            } else if (hdr == 0x90) {  // Temperature
                if (i + 3 >= buf.size) break
                val uncomp = ((buf[i + 3].toInt() and 0xFF) shl 16) or
                             ((buf[i + 2].toInt() and 0xFF) shl 8) or
                             (buf[i + 1].toInt() and 0xFF)
                val t = compensateTemperature(uncomp)
                frames.add(FifoFrame("temperature", t))
                i += 4
            } else if (hdr == 0xA0) {  // Sensortime
                if (i + 3 >= buf.size) break
                val uncomp = ((buf[i + 3].toInt() and 0xFF) shl 16) or
                             ((buf[i + 2].toInt() and 0xFF) shl 8) or
                             (buf[i + 1].toInt() and 0xFF)
                frames.add(FifoFrame("sensortime", uncomp.toDouble()))
                i += 4
            } else if (hdr == 0x44 || hdr == 0x80) {  // Error or Empty
                frames.add(FifoFrame(if (hdr == 0x44) "error" else "empty", 0.0))
                i += 1
            } else {
                frames.add(FifoFrame("unknown", 0.0))
                i += 1
            }
        }
        return frames
    }

    /** Flush the FIFO contents. */
    fun fifoFlush() {
        writeReg(REG_CMD, FIFO_FLUSH_CMD)
    }

    /** Compute altitude above sea level from the current pressure. */
    fun altitude(seaLevelHpa: Double = 1013.25): Double {
        val p = pressure()
        if (p <= 0.0) return 0.0
        return 44330.0 * (1.0 - Math.pow(p / seaLevelHpa, 1.0 / 5.255))
    }

    private fun triggerForced() {
        writeReg(REG_PWR_CTRL, (MODE_FORCED shl 4) or PWR_TEMP_EN or PWR_PRESS_EN)
    }

    private fun applyPwr() {
        writeReg(REG_PWR_CTRL, (mode shl 4) or PWR_TEMP_EN or PWR_PRESS_EN)
    }

    private fun compensatePressureWithTLin(uncompPress: Int, tLin: Double): Double {
        this.tLin = tLin
        return compensatePressure(uncompPress)
    }

    private fun computeTConvMs(osrP: Int, osrT: Int): Int {
        // T_conv per spec: 234 + 392 + 2^osr_p*2000 + 313 + 2^osr_t*2000 µs.
        val tConvUs = 234L
            + 392L + (1L shl osrP) * 2000L
            + 313L + (1L shl osrT) * 2000L
        return ((tConvUs + 999) / 1000).toInt()
    }
}
