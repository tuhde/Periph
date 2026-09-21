package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * Output pin abstraction for the optional hardware RESET line.
 */
fun interface Ad7705ResetPin {
    /**
     * Drive the pin.
     * @param high `true` to drive high, `false` to drive low.
     */
    @Throws(IOException::class)
    fun set(high: Boolean)
}

/**
 * AD7705 — 2-channel, 16-bit sigma-delta ADC with SPI interface (full driver).
 *
 * Extends [Ad7705Minimal] with per-channel configuration, all eight PGA gains,
 * unipolar/bipolar selection, buffered/unbuffered analog inputs, all four
 * output rates within the mclkHz family, self- and system-calibration, direct
 * access to the 24-bit calibration coefficients, standby/wakeup power control,
 * and an optional hardware reset pin.
 */
class Ad7705Full @JvmOverloads constructor(
    connection: Connection,
    vref: Float,
    mclkHz: Int,
    private val resetPin: Ad7705ResetPin? = null,
) : Ad7705Minimal(connection, vref, mclkHz) {

    companion object {
        const val GAIN_1   = 0
        const val GAIN_2   = 1
        const val GAIN_4   = 2
        const val GAIN_8   = 3
        const val GAIN_16  = 4
        const val GAIN_32  = 5
        const val GAIN_64  = 6
        const val GAIN_128 = 7
    }

    /**
     * Write the Setup and Clock Registers for the given channel.
     *
     * Does not calibrate — call [selfCalibrate] (or one of the
     * system-calibration methods) afterward. The first readRaw/readVoltage
     * after a configuration change may be stale; recalibration is recommended
     * whenever gain, filter notch, or bipolar/unipolar mode changes.
     */
    @Throws(IOException::class)
    fun configure(channel: Int, gain: Int, bipolar: Boolean, buffered: Boolean, outputRateHz: Int) {
        require(channel == 1 || channel == 2) { "channel must be 1 or 2" }
        require(gain == 1 || gain == 2 || gain == 4 || gain == 8 || gain == 16 || gain == 32 || gain == 64 || gain == 128) {
            "gain must be one of 1, 2, 4, 8, 16, 32, 64, 128"
        }
        val ch = if (channel == 1) CH1 else CH2
        val rates = if (mclkHz >= MCLK_2_4576MHZ) FS_RATES_2_4MHZ else FS_RATES_1MHZ
        require(outputRateHz in rates) { "outputRateHz must be one of ${rates.contentToString()}" }

        configureClock(outputRateHz)

        val buBit = if (bipolar) BIPOLAR else UNIPOLAR
        val bufBit = if (buffered) BUFFERED else UNBUFFERED
        val gainIdx = when (gain) {
            1 -> 0; 2 -> 1; 4 -> 2; 8 -> 3; 16 -> 4; 32 -> 5; 64 -> 6; 128 -> 7
            else -> throw IllegalArgumentException("gain out of range")
        }
        val setup = MODE_NORMAL or GAIN_BITS[gainIdx] or buBit or bufBit or FSYNC_RUN
        writeRegChannel(REG_SETUP, setup, ch, 1)

        if (channel == 1) {
            this.gain = gain
            this.bipolar = bipolar
            this.buffered = buffered
        }
    }

    /** Block until DRDY, then read the raw 16-bit code for the channel. */
    @Throws(IOException::class)
    fun readRaw(channel: Int): Int {
        require(channel == 1 || channel == 2) { "channel must be 1 or 2" }
        val ch = if (channel == 1) CH1 else CH2
        waitDrdy()
        return readRegChannel(REG_DATA, ch, 2)
    }

    /** Block until DRDY, then return the input voltage on the channel in V. */
    @Throws(IOException::class)
    fun readVoltage(channel: Int): Float {
        val code = readRaw(channel)
        return codeToVoltage(code, gain, bipolar)
    }

    /** Run an internal self-calibration on the channel. */
    @Throws(IOException::class)
    fun selfCalibrate(channel: Int) = runCalibration(channel, MODE_SELF_CAL)

    /** Run a zero-scale system calibration. The caller must present the zero-scale voltage at AIN first. */
    @Throws(IOException::class)
    fun systemCalibrateZero(channel: Int) = runCalibration(channel, MODE_ZERO_SYS)

    /** Run a full-scale system calibration. The caller must present the full-scale voltage at AIN first. */
    @Throws(IOException::class)
    fun systemCalibrateFull(channel: Int) = runCalibration(channel, MODE_FULL_SYS)

    private fun runCalibration(channel: Int, mode: Int) {
        require(channel == 1 || channel == 2) { "channel must be 1 or 2" }
        val ch = if (channel == 1) CH1 else CH2
        val buBit = if (bipolar) BIPOLAR else UNIPOLAR
        val bufBit = if (buffered) BUFFERED else UNBUFFERED
        val gainIdx = when (gain) {
            1 -> 0; 2 -> 1; 4 -> 2; 8 -> 3; 16 -> 4; 32 -> 5; 64 -> 6; 128 -> 7
            else -> throw IllegalStateException("gain out of range")
        }
        val setup = mode or GAIN_BITS[gainIdx] or buBit or bufBit or FSYNC_RUN
        writeRegChannel(REG_SETUP, setup, ch, 1)
        waitDrdy()
    }

    /** Read the 24-bit Zero-Scale Calibration Register for the channel. */
    @Throws(IOException::class)
    fun getOffsetCalibration(channel: Int): Int {
        val ch = if (channel == 1) CH1 else CH2
        return readRegChannel(REG_OFFSET, ch, 3)
    }

    /** Write a 24-bit Zero-Scale Calibration Register for the channel. */
    @Throws(IOException::class)
    fun setOffsetCalibration(value: Int, channel: Int) {
        val ch = if (channel == 1) CH1 else CH2
        writeRegChannel(REG_OFFSET, value and 0xFFFFFF, ch, 3)
    }

    /** Read the 24-bit Full-Scale Calibration Register for the channel. */
    @Throws(IOException::class)
    fun getGainCalibration(channel: Int): Int {
        val ch = if (channel == 1) CH1 else CH2
        return readRegChannel(REG_GAIN, ch, 3)
    }

    /** Write a 24-bit Full-Scale Calibration Register for the channel. */
    @Throws(IOException::class)
    fun setGainCalibration(value: Int, channel: Int) {
        val ch = if (channel == 1) CH1 else CH2
        writeRegChannel(REG_GAIN, value and 0xFFFFFF, ch, 3)
    }

    /** Enter standby/power-down (~10 µA). Registers are retained. */
    @Throws(IOException::class)
    fun standby() {
        connection.write(byteArrayOf((commByte(REG_COMM, false, CH1) or STBY_SLEEP).toByte()))
    }

    /** Exit standby and block until a fresh conversion is available. */
    @Throws(IOException::class)
    fun wakeup() {
        connection.write(byteArrayOf((commByte(REG_COMM, false, CH1) or STBY_RUN).toByte()))
        waitDrdy()
    }

    /** Pulse the hardware RESET line. Requires a resetPin to have been supplied. */
    @Throws(IOException::class)
    fun reset() {
        checkNotNull(resetPin) { "reset() requires a resetPin to have been supplied at construction" }
        resetPin.set(false)
        resetPin.set(true)
    }
}
