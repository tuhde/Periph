package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * BMP581 — full driver. Extends [Bmp581Minimal] with configuration, FIFO,
 * interrupts, OOR detection, and NVM access.
 */
class Bmp581Full @JvmOverloads constructor(
    connection: Connection,
    busType: Int = Bmp581Minimal.BUS_I2C,
    addr: Int = 0x46,
) : Bmp581Minimal(connection, busType, addr) {

    private var osrP: Int = 0
    private var osrT: Int = 0
    private var pressEn: Boolean = true

    /**
     * Write OSR_CONFIG and ODR_CONFIG atomically.
     *
     * @param odr ODR field 0x00-0x1F (default 0x1C = 1 Hz).
     * @param osrP Pressure oversampling 0-7.
     * @param osrT Temperature oversampling 0-7.
     * @param pressEn Whether to enable pressure measurements.
     * @throws IOException on bus error.
     */
    fun configure(odr: Int, osrP: Int, osrT: Int, pressEn: Boolean) {
        this.odr = odr
        this.osrP = osrP
        this.osrT = osrT
        this.pressEn = pressEn
        val osr = (if (pressEn) 0x40 else 0) or ((osrP and 0x7) shl 3) or (osrT and 0x7)
        writeReg(REG_OSR_CONFIG, osr)
        val odrByte = ((odr and 0x1F) shl 2) or (pwrMode and 0x3)
        writeReg(REG_ODR_CONFIG, odrByte)
    }

    /**
     * Set the power mode (preserves the current ODR setting).
     */
    fun setMode(mode: Int) {
        this.pwrMode = mode
        val odrByte = ((odr and 0x1F) shl 2) or (mode and 0x3)
        writeReg(REG_ODR_CONFIG, odrByte)
    }

    /** Trigger a single FORCED measurement, wait for completion, return the readings. */
    fun forced(): Pair<Double, Double> {
        val prev = pwrMode
        if (prev != MODE_FORCED) setMode(MODE_FORCED)
        for (i in 0 until 400) {
            val st = connection.writeRead(byteArrayOf(REG_INT_STATUS.toByte()), 1)
            if ((st[0].toInt() and INT_STATUS_DRDY) != 0) break
            try { Thread.sleep(5) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        return both()
    }

    /**
     * Compute altitude above sea level from the current pressure.
     *
     * @param seaLevelPa Reference pressure in Pa (default 101325).
     * @return Altitude in metres.
     */
    fun altitude(seaLevelPa: Double = 101325.0): Double {
        val p = pressure()
        return if (p <= 0) 0.0 else 44330.0 * (1.0 - Math.pow(p / seaLevelPa, 1.0 / 5.255))
    }

    /** Issue a soft reset and re-initialise the chip. */
    fun softwareReset() {
        try {
            writeReg(REG_CMD, SOFT_RESET)
        } catch (e: IOException) { /* expected NACK */ }
        try { Thread.sleep(2) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        init(0x46)
        configure(odr, osrP, osrT, pressEn)
    }

    /** Read CHIP_ID. @return 0x50 for a genuine BMP581. */
    fun chipId(): Int {
        val buf = connection.writeRead(byteArrayOf(REG_CHIP_ID.toByte()), 1)
        return buf[0].toInt() and 0xFF
    }

    /** Read REV_ID. */
    fun revId(): Int {
        val buf = connection.writeRead(byteArrayOf(REG_REV_ID.toByte()), 1)
        return buf[0].toInt() and 0xFF
    }

    /** Read STATUS. */
    fun status(): Int {
        val buf = connection.writeRead(byteArrayOf(REG_STATUS.toByte()), 1)
        return buf[0].toInt() and 0xFF
    }

    /** Read INT_STATUS (clear-on-read). */
    fun interruptStatus(): Int {
        val buf = connection.writeRead(byteArrayOf(REG_INT_STATUS.toByte()), 1)
        return buf[0].toInt() and 0xFF
    }

    /** Check whether a new data sample is available. */
    fun dataReady(): Boolean = (interruptStatus() and INT_STATUS_DRDY) != 0

    /** Configure INT pin: latching, polarity, drive mode, pin enable. */
    fun configureInterrupt(mode: Int, polarity: Int, openDrain: Boolean, enable: Boolean) {
        var val = if (enable) 0x08 else 0
        if (openDrain) val = val or 0x04
        if (polarity != 0) val = val or 0x02
        if (mode != 0) val = val or 0x01
        writeReg(REG_INT_CONFIG, val)
    }

    private fun setIntSource(source: Int, enable: Boolean) {
        val buf = connection.writeRead(byteArrayOf(REG_INT_SOURCE.toByte()), 1)
        val cur = buf[0].toInt() and 0xFF
        val next = if (enable) (cur or source) else (cur and source.inv())
        writeReg(REG_INT_SOURCE, next)
    }

    /** Enable or disable the data-ready interrupt source. */
    fun enableDrdyInterrupt(enable: Boolean) = setIntSource(INT_SOURCE_DRDY, enable)

    /** Enable or disable FIFO threshold and FIFO-full interrupt sources. */
    fun enableFifoInterrupt(threshold: Boolean, full: Boolean) {
        val buf = connection.writeRead(byteArrayOf(REG_INT_SOURCE.toByte()), 1)
        var cur = buf[0].toInt() and 0xFF
        cur = cur and (INT_SOURCE_FIFO_FULL or INT_SOURCE_FIFO_THS).inv()
        if (threshold) cur = cur or INT_SOURCE_FIFO_THS
        if (full) cur = cur or INT_SOURCE_FIFO_FULL
        writeReg(REG_INT_SOURCE, cur)
    }

    /** Enable or disable the pressure out-of-range interrupt source. */
    fun enableOorInterrupt(enable: Boolean) = setIntSource(INT_SOURCE_OOR_P, enable)

    /**
     * Set IIR filter coefficients for pressure and temperature. Also sets
     * shdw_sel_iir_p/t in DSP_CONFIG so the data registers hold post-IIR values.
     */
    fun setIirFilter(coeffP: Int, coeffT: Int) {
        val buf = connection.writeRead(byteArrayOf(REG_DSP_CONFIG.toByte()), 1)
        val dsp = (buf[0].toInt() and 0xFF) or 0x28
        writeReg(REG_DSP_CONFIG, dsp)
        val iirVal = ((coeffP and 0x7) shl 3) or (coeffT and 0x7)
        writeReg(REG_DSP_IIR, iirVal)
    }

    /**
     * Configure FIFO source, mode, and threshold. Must be called in STANDBY mode.
     */
    fun configureFifo(frameSel: Int, mode: Int, threshold: Int) {
        val prev = pwrMode
        if (prev != MODE_STANDBY) setMode(MODE_STANDBY)
        writeReg(REG_FIFO_SEL, frameSel and 0x3)
        val cfg = ((mode and 0x1) shl 5) or (threshold and 0x1F)
        writeReg(REG_FIFO_CONFIG, cfg)
        if (prev != MODE_STANDBY) setMode(prev)
    }

    /** Read the number of frames currently in the FIFO. */
    fun fifoCount(): Int {
        val buf = connection.writeRead(byteArrayOf(REG_FIFO_COUNT.toByte()), 1)
        return buf[0].toInt() and 0x3F
    }

    /** Read OSR_EFF. @return Pair of (osrP_eff, osrT_eff). */
    fun effectiveOsr(): Pair<Int, Int> {
        val buf = connection.writeRead(byteArrayOf(REG_OSR_EFF.toByte()), 1)
        return ((buf[0].toInt() shr 3) and 0x7) to (buf[0].toInt() and 0x7)
    }

    /** Check whether the current ODR/OSR combination is valid. */
    fun odrIsValid(): Boolean {
        val buf = connection.writeRead(byteArrayOf(REG_OSR_EFF.toByte()), 1)
        return (buf[0].toInt() and 0x80) != 0
    }

    /** Configure the out-of-range pressure detector. */
    fun setOorThreshold(thresholdPa: Double, rangePa: Double, countLimit: Int) {
        val thr17 = (thresholdPa * 64.0).toInt() shr 7
        val oorThrP16 = (thr17 shr 16) and 0x01
        writeReg(REG_OOR_THR_P_LSB, thr17 and 0xFF)
        writeReg(REG_OOR_THR_P_MSB, (thr17 shr 8) and 0xFF)
        val range8 = ((rangePa * 64.0).toInt() shr 7) and 0xFF
        writeReg(REG_OOR_RANGE, range8)
        val cfg = ((countLimit and 0x3) shl 6) or oorThrP16
        writeReg(REG_OOR_CONFIG, cfg)
    }

    /** Read one user NVM row (rows 0x20..0x22). */
    fun nvmRead(row: Int): Int {
        val prev = pwrMode
        if (prev != MODE_STANDBY) setMode(MODE_STANDBY)
        try {
            writeReg(REG_NVM_ADDR, 0x5D)
            writeReg(REG_CMD, 0xA5)
            try { Thread.sleep(2) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
            writeReg(REG_NVM_ADDR, 0x40 or (row and 0x3F))
            writeReg(REG_CMD, 0xA5)
            try { Thread.sleep(2) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
            val buf = connection.writeRead(byteArrayOf(REG_NVM_DATA_LSB.toByte()), 2)
            return ((buf[1].toInt() and 0xFF) shl 8) or (buf[0].toInt() and 0xFF)
        } finally {
            if (prev != MODE_STANDBY) setMode(prev)
        }
    }

    /** Write one user NVM row. Limited to 10,000 total write cycles. */
    fun nvmWrite(row: Int, value: Int) {
        val prev = pwrMode
        if (prev != MODE_STANDBY) setMode(MODE_STANDBY)
        try {
            writeReg(REG_NVM_ADDR, 0x40 or (row and 0x3F))
            writeReg(REG_NVM_DATA_LSB, value and 0xFF)
            writeReg(REG_NVM_DATA_MSB, (value shr 8) and 0xFF)
            writeReg(REG_NVM_ADDR, 0x5D)
            writeReg(REG_CMD, 0xA0)
            try { Thread.sleep(5) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        } finally {
            if (prev != MODE_STANDBY) setMode(prev)
        }
    }

    companion object {
        /** Pressure oversampling: ×1. */
        const val OSR_1X = 0
        /** Pressure oversampling: ×2. */
        const val OSR_2X = 1
        /** Pressure oversampling: ×4. */
        const val OSR_4X = 2
        /** Pressure oversampling: ×8. */
        const val OSR_8X = 3
        /** Pressure oversampling: ×16. */
        const val OSR_16X = 4
        /** Pressure oversampling: ×32. */
        const val OSR_32X = 5
        /** Pressure oversampling: ×64. */
        const val OSR_64X = 6
        /** Pressure oversampling: ×128. */
        const val OSR_128X = 7

        /** Power mode: standby. */
        const val MODE_STANDBY = 0
        /** Power mode: normal. */
        const val MODE_NORMAL = 1
        /** Power mode: forced. */
        const val MODE_FORCED = 2
        /** Power mode: continuous. */
        const val MODE_CONTINUOUS = 3

        /** IIR filter: bypass. */
        const val IIR_BYPASS = 0
        /** IIR filter: coefficient 1. */
        const val IIR_COEFF_1 = 1
        /** IIR filter: coefficient 3. */
        const val IIR_COEFF_3 = 2
        /** IIR filter: coefficient 7. */
        const val IIR_COEFF_7 = 3
        /** IIR filter: coefficient 15. */
        const val IIR_COEFF_15 = 4
        /** IIR filter: coefficient 31. */
        const val IIR_COEFF_31 = 5
        /** IIR filter: coefficient 63. */
        const val IIR_COEFF_63 = 6
        /** IIR filter: coefficient 127. */
        const val IIR_COEFF_127 = 7

        /** FIFO frame selection: disabled. */
        const val FIFO_DISABLED = 0
        /** FIFO frame selection: temperature only. */
        const val FIFO_TEMP = 1
        /** FIFO frame selection: pressure only. */
        const val FIFO_PRESS = 2
        /** FIFO frame selection: pressure + temperature. */
        const val FIFO_BOTH = 3

        /** FIFO mode: stream. */
        const val FIFO_STREAM = 0
        /** FIFO mode: stop-on-full. */
        const val FIFO_STOP_ON_FULL = 1

        /** INT_SOURCE bit: data-ready. */
        const val INT_SOURCE_DRDY = 0x01
        /** INT_SOURCE bit: FIFO full. */
        const val INT_SOURCE_FIFO_FULL = 0x02
        /** INT_SOURCE bit: FIFO threshold. */
        const val INT_SOURCE_FIFO_THS = 0x04
        /** INT_SOURCE bit: pressure out-of-range. */
        const val INT_SOURCE_OOR_P = 0x08

        // Full-only register addresses.
        private const val REG_REV_ID = 0x02
        private const val REG_INT_SOURCE = 0x15
        private const val REG_INT_CONFIG = 0x14
        private const val REG_FIFO_SEL = 0x18
        private const val REG_FIFO_CONFIG = 0x16
        private const val REG_FIFO_COUNT = 0x17
        private const val REG_DSP_CONFIG = 0x30
        private const val REG_DSP_IIR = 0x31
        private const val REG_OOR_THR_P_LSB = 0x32
        private const val REG_OOR_THR_P_MSB = 0x33
        private const val REG_OOR_RANGE = 0x34
        private const val REG_OOR_CONFIG = 0x35
        private const val REG_OSR_EFF = 0x38
        private const val REG_NVM_ADDR = 0x2B
        private const val REG_NVM_DATA_LSB = 0x2C
        private const val REG_NVM_DATA_MSB = 0x2D
    }
}