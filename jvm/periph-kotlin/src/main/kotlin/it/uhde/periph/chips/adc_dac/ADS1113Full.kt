package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection

/**
 * ADS1113 full interface — extends [ADS1113Minimal] with data rate, continuous
 * conversion, status and reset.
 *
 * @param connection I²C register connection pointing at the device
 */
class ADS1113Full(connection: RegisterConnection) : ADS1113Minimal(connection) {

    companion object {
        /** Default 7-bit I²C address (`ADDR` = GND). Valid range 0x48–0x4B. */
        const val DEFAULT_ADDRESS = 0x48

        /** Data-rate codes (`DR[2:0]`). */
        const val DR_8 = 0
        const val DR_16 = 1
        const val DR_32 = 2
        const val DR_64 = 3
        const val DR_128 = 4
        const val DR_250 = 5
        const val DR_475 = 6
        const val DR_860 = 7
    }

    /** Run a single-shot conversion and return volts at the fixed ±2.048 V range. */
    override fun readVoltage(): Double = readRaw() * doLsb()

    /** Start continuous conversion at the current data rate (`MODE` ← 0). */
    fun startContinuous() = doStartContinuous(0)

    /** ±FSR of the current gain in volts. */
    fun fullScale(): Double = doFullScale()

    /** Size of one code in volts (`fullScale() / 32768`). */
    fun lsb(): Double = doLsb()

    /** Set the data rate used by all following conversions ([DR_8]…[DR_860]). */
    fun setDataRate(rate: Int) = doSetDataRate(rate)

    /** The configured `DR` code (0–7). */
    fun getDataRate(): Int = dr

    /** The configured data rate in samples per second. */
    fun dataRateSps(): Int = RATES[dr]

    /** Read the most recent completed result without touching Config. */
    fun readContinuousRaw(): Int = doReadContinuousRaw()

    /** Read the most recent continuous result in volts. */
    fun readContinuous(): Double = doReadContinuousRaw() * doLsb()

    /** Return to single-shot / power-down mode (`MODE` ← 1). */
    fun stopContinuous() = doStopContinuous()

    /** `true` while `CONFIG.OS` reads 0 (single-shot mode only). */
    fun isConverting(): Boolean = doIsConverting()

    /** The raw 16-bit `CONFIG` register. */
    fun readConfig(): Int = readReg(REG_CONFIG)

    /**
     * Write the reset values back to CONFIG and clear the cached
     * settings. Not an I²C general-call reset.
     */
    fun reset() = doReset(false)
}
