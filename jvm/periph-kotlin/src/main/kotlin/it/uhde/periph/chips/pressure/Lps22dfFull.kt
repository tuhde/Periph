package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * LPS22DF full driver — extends [Lps22dfMinimal] with configuration,
 * threshold/offset calibration, FIFO, interrupts, and AUTOZERO/AUTOREFP.
 */
class Lps22dfFull @JvmOverloads constructor(
    conn: Connection,
    addr: Int = 0x5C,
    busType: Int = Lps22dfMinimal.BUS_I2C
) : Lps22dfMinimal(conn, addr, busType) {

    companion object {
        /** Output data rate: power-down. */
        const val ODR_POWER_DOWN = 0
        const val ODR_1_HZ       = 1
        const val ODR_4_HZ       = 2
        const val ODR_10_HZ      = 3
        const val ODR_25_HZ      = 4
        const val ODR_50_HZ      = 5
        const val ODR_75_HZ      = 6
        const val ODR_100_HZ     = 7
        const val ODR_200_HZ     = 8

        const val AVG_4   = 0
        const val AVG_8   = 1
        const val AVG_16  = 2
        const val AVG_32  = 3
        const val AVG_64  = 4
        const val AVG_128 = 5
        const val AVG_512 = 7

        const val FIFO_BYPASS         = 0
        const val FIFO_FIFO           = 1
        const val FIFO_CONTINUOUS     = 2
        const val FIFO_BYPASS_TO_FIFO = 3
        const val FIFO_BYPASS_TO_CONT = 4
        const val FIFO_CONT_TO_FIFO   = 5
    }

    /**
     * Write CTRL_REG1 and CTRL_REG2.
     *
     * @param odr      output data rate (0=power-down, 1..7=1..100 Hz, 8=200 Hz)
     * @param avg      averaging filter (0=4, 1=8, 2=16, 3=32, 4=64, 5=128, 7=512)
     * @param enLpfp   enable low-pass filter on pressure output
     * @param lfpfCfg  0=ODR/4 cutoff, 1=ODR/9 cutoff
     * @param bdu      block data update
     */
    @Throws(IOException::class)
    fun configure(odr: Int, avg: Int, enLpfp: Boolean, lfpfCfg: Int, bdu: Boolean) {
        val ctrl1 = ((odr and 0x0F) shl 3) or (avg and 0x07)
        var ctrl2 = 0
        if (enLpfp)         ctrl2 = ctrl2 or 0x10
        if (lfpfCfg != 0)   ctrl2 = ctrl2 or 0x20
        if (bdu)            ctrl2 = ctrl2 or 0x08
        writeReg(REG_CTRL_REG1, ctrl1)
        writeReg(REG_CTRL_REG2, ctrl2)
    }

    /** Trigger a single measurement in power-down mode; blocks until P_DA. */
    @Throws(IOException::class)
    fun oneshot() {
        writeReg(REG_CTRL_REG1, 0x00)
        writeReg(REG_CTRL_REG2, 0x08 or 0x01)
        waitPDa()
    }

    /**
     * Compute altitude above sea level from the current pressure.
     *
     * @param seaLevelPa reference sea-level pressure in pascals (default 101325)
     * @return altitude in metres
     */
    @Throws(IOException::class)
    fun altitude(seaLevelPa: Double = 101325.0): Double {
        val p = pressure()
        return 44330.0 * (1.0 - Math.pow(p / seaLevelPa, 1.0 / 5.255))
    }

    /** Software-reset the chip and wait for self-clear. */
    @Throws(IOException::class)
    fun softwareReset() {
        writeReg(REG_CTRL_REG2, 0x04)
        Thread.sleep(1)
    }

    /** Write a one-point calibration offset. */
    @Throws(IOException::class)
    fun setPressureOffset(offsetPa: Double) {
        val offsetHpa = offsetPa / 100.0
        var raw = Math.round(offsetHpa * 4096.0).toInt()
        if (raw < 0) raw += 0x10000
        writeReg(REG_RPDS_L, raw and 0xFF)
        writeReg(REG_RPDS_H, (raw shr 8) and 0xFF)
    }

    /** Write a 15-bit unsigned pressure threshold. */
    @Throws(IOException::class)
    fun setPressureThreshold(thresholdPa: Double) {
        val thresholdHpa = thresholdPa / 100.0
        val raw = (Math.round(thresholdHpa * 16.0).toInt()) and 0x7FFF
        writeReg(REG_THS_P_L, raw and 0xFF)
        writeReg(REG_THS_P_H, (raw shr 8) and 0xFF)
    }

    /** Configure the INT pin and routing. */
    @Throws(IOException::class)
    fun configureInterrupt(intHL: Boolean, ppOd: Boolean, drdy: Boolean, drdyPls: Boolean,
                           intEn: Boolean, intFWtm: Boolean, intFFull: Boolean, intFOvr: Boolean) {
        var ctrl3 = 0x01
        if (intHL) ctrl3 = ctrl3 or 0x08
        if (ppOd)  ctrl3 = ctrl3 or 0x02
        var ctrl4 = 0
        if (drdyPls)  ctrl4 = ctrl4 or 0x40
        if (drdy)     ctrl4 = ctrl4 or 0x20
        if (intEn)    ctrl4 = ctrl4 or 0x10
        if (intFFull) ctrl4 = ctrl4 or 0x04
        if (intFWtm)  ctrl4 = ctrl4 or 0x02
        if (intFOvr)  ctrl4 = ctrl4 or 0x01
        writeReg(REG_CTRL_REG3, ctrl3)
        writeReg(REG_CTRL_REG4, ctrl4)
    }

    /** Configure pressure-event interrupts. */
    @Throws(IOException::class)
    fun configurePressureEvent(phe: Boolean, ple: Boolean, lir: Boolean) {
        var cfg = 0
        if (phe) cfg = cfg or 0x01
        if (ple) cfg = cfg or 0x02
        if (lir) cfg = cfg or 0x04
        writeReg(REG_INTERRUPT_CFG, cfg)
    }

    /** Capture the current pressure as the AUTOZERO reference. */
    @Throws(IOException::class)
    fun autozero() { writeReg(REG_INTERRUPT_CFG, 0x20) }

    /** Capture the current pressure in REF_P for use as a comparator. */
    @Throws(IOException::class)
    fun autorefp() { writeReg(REG_INTERRUPT_CFG, 0x80) }

    /** Reset both AUTOZERO and AUTOREFP, returning PRESS_OUT to absolute. */
    @Throws(IOException::class)
    fun resetReference() { writeReg(REG_INTERRUPT_CFG, 0x50) }

    /** Read the stored AUTOZERO/AUTOREFP reference pressure in pascals. */
    @Throws(IOException::class)
    fun referencePressure(): Double {
        val raw = readReg(REG_REF_P_L, 2)
        val s = ((raw[0].toInt() and 0xFF) shl 0) or ((raw[1].toInt() and 0xFF) shl 8)
        return (s.toShort().toDouble() / 4096.0) * 100.0
    }

    /** Set the FIFO mode. */
    @Throws(IOException::class)
    fun setFifoMode(mode: Int) {
        val (trig, fm) = when (mode) {
            0 -> 0 to 0
            1 -> 0 to 1
            2 -> 0 to 2
            3 -> 1 to 1
            4 -> 1 to 2
            else -> 1 to 3
        }
        writeReg(REG_FIFO_CTRL, (trig shl 2) or (fm and 0x03))
    }

    /** Set the FIFO watermark level (0..127). */
    @Throws(IOException::class)
    fun setFifoWatermark(level: Int) {
        writeReg(REG_FIFO_WTM, level and 0x7F)
    }

    /** Read the FIFO sample count. */
    @Throws(IOException::class)
    fun fifoSampleCount(): Int {
        val v = readReg(REG_FIFO_STATUS1, 1)
        return v[0].toInt() and 0xFF
    }

    /**
     * Read every available FIFO sample.
     *
     * @param out destination array; one double per sample, in pascals
     * @return number of samples written into out
     */
    @Throws(IOException::class)
    fun readFifo(out: DoubleArray): Int {
        val count = fifoSampleCount()
        if (count == 0) return 0
        val n = minOf(count, out.size)
        val raw = readReg(REG_FIFO_PRESS_XL, n * 3)
        for (i in 0 until n) {
            val base = i * 3
            var value = (raw[base].toInt() and 0xFF) or
                        ((raw[base + 1].toInt() and 0xFF) shl 8) or
                        ((raw[base + 2].toInt() and 0xFF) shl 16)
            if ((value and 0x800000) != 0) value -= 0x1000000
            out[i] = (value / 4096.0) * 100.0
        }
        return n
    }

    /** Read and clear the INT_SOURCE register. */
    @Throws(IOException::class)
    fun interruptSource(): Int {
        val v = readReg(REG_INT_SOURCE, 1)
        return v[0].toInt() and 0xFF
    }
}