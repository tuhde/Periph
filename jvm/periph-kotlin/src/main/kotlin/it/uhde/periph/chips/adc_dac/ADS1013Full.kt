package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection

/**
 * ADS1013 full interface — extends [ADS1013Minimal] with data-rate selection
 * and continuous conversion.
 *
 * @param connection configured I²C connection bound to the device (0x48–0x4B)
 */
class ADS1013Full(connection: RegisterConnection) : ADS1013Minimal(connection) {

    companion object {
        /** Default I²C address (`ADDR` = GND). Valid range 0x48–0x4B. */
        const val DEFAULT_ADDRESS = ADS1013Minimal.DEFAULT_ADDRESS

        /** Data rate 128 SPS. */
        const val DR_128 = 0
        /** Data rate 250 SPS. */
        const val DR_250 = 1
        /** Data rate 490 SPS. */
        const val DR_490 = 2
        /** Data rate 920 SPS. */
        const val DR_920 = 3
        /** Data rate 1600 SPS (default). */
        const val DR_1600 = 4
        /** Data rate 2400 SPS. */
        const val DR_2400 = 5
        /** Data rate 3300 SPS. */
        const val DR_3300 = 6

        private const val CONFIG_RESET = 0x8583
    }

    private fun writeConfig() = writeReg(REG_CONFIG, configWord(false, continuous))

    /** Fixed full-scale range: always 2.048 (±V). */
    fun fullScale(): Double = 2.048

    /** Size of one conversion code: always 0.001 V. */
    fun lsb(): Double = LSB

    /**
     * Select the conversion data rate.
     *
     * @param dr [DR_128] … [DR_3300]
     * @throws IllegalArgumentException if [dr] is outside 0–6 (7 aliases 3300 SPS)
     */
    fun setDataRate(dr: Int) {
        require(dr in DR_128..DR_3300) { "dr must be 0-6" }
        this.dr = dr
        if (continuous) writeConfig()
    }

    /** Read the configured `DR` code (0–6). */
    fun getDataRate(): Int = dr

    /** Current data rate: 128, 250, 490, 920, 1600, 2400 or 3300 samples per second. */
    fun dataRateSps(): Int = SPS[dr]

    /** Start continuous conversion (`MODE` = 0) at the current data rate. */
    fun startContinuous() {
        continuous = true
        writeConfig()
    }

    /**
     * Read the most recent completed result without touching Config (valid at
     * least 1/DR after [startContinuous]).
     *
     * @return 12-bit two's-complement code, −2048…2047
     */
    fun readContinuousRaw(): Int = readConversion()

    /** Read the most recent completed result in volts: `readContinuousRaw() × lsb()`. */
    fun readContinuous(): Double = readConversion() * LSB

    /** Stop continuous conversion (`MODE` = 1, single-shot / power-down). */
    fun stopContinuous() {
        continuous = false
        writeConfig()
    }

    /**
     * Report whether a single-shot conversion is running (`OS` = 0; only
     * meaningful in single-shot mode).
     */
    fun isConverting(): Boolean = readReg(REG_CONFIG) and CFG_OS == 0

    /** Read the raw 16-bit CONFIG register. */
    fun readConfig(): Int = readReg(REG_CONFIG)

    /** Write `CONFIG` = 0x8583 back and clear the cached settings. Not an I²C general-call reset. */
    fun reset() {
        writeReg(REG_CONFIG, CONFIG_RESET)
        dr = DR_1600
        continuous = false
    }
}
