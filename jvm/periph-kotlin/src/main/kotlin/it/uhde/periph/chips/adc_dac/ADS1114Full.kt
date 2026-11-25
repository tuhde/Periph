package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

/**
 * ADS1114 full interface — extends [ADS1114Minimal] with gain, data rate, continuous
 * conversion, status and reset, the comparator, thresholds and the `ALERT/RDY` interrupt API.
 *
 * @param connection I²C register connection pointing at the device
 */
class ADS1114Full(connection: RegisterConnection) : ADS1114Minimal(connection) {

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

        /** Full-scale range codes (`PGA[2:0]`). */
        const val PGA_6_144 = 0
        const val PGA_4_096 = 1
        const val PGA_2_048 = 2
        const val PGA_1_024 = 3
        const val PGA_0_512 = 4
        const val PGA_0_256 = 5

        /** Comparator mode, polarity and queue values. */
        const val COMP_TRADITIONAL = 0
        const val COMP_WINDOW = 1
        const val ACTIVE_LOW = 0
        const val ACTIVE_HIGH = 1
        const val QUEUE_1 = 0
        const val QUEUE_2 = 1
        const val QUEUE_4 = 2

        /** Interrupt sources. */
        const val SOURCE_CONVERSION_READY = ADS111xBase.SOURCE_CONVERSION_READY
        const val SOURCE_COMPARATOR = ADS111xBase.SOURCE_COMPARATOR
    }

    /** Run a single-shot conversion and return volts at the current gain. */
    override fun readVoltage(): Double = readRaw() * doLsb()

    /** Start continuous conversion at the current data rate (`MODE` ← 0). */
    fun startContinuous() = doStartContinuous(0)

    /**
     * Set the full-scale range for all following reads; thresholds are not rescaled.
     *
     * @param code [PGA_6_144]…[PGA_0_256] (0–5)
     */
    fun setGain(code: Int) = doSetGain(code)

    /** The configured `PGA` code (0–5). */
    fun getGain(): Int = pga

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
     * Write the reset values back to CONFIG, LO_THRESH and HI_THRESH and clear the cached
     * settings. Not an I²C general-call reset.
     */
    fun reset() = doReset(true)

    /**
     * Configure the comparator and route it to `ALERT/RDY`.
     *
     * @param mode [COMP_TRADITIONAL] or [COMP_WINDOW]
     * @param polarity [ACTIVE_LOW] or [ACTIVE_HIGH]
     * @param latch `true` to latch until [clearAlert]
     * @param queue [QUEUE_1], [QUEUE_2] or [QUEUE_4]
     * @throws IllegalArgumentException on an invalid argument or when the last thresholds have high ≤ low
     */
    fun configureComparator(
        mode: Int = COMP_TRADITIONAL,
        polarity: Int = ACTIVE_LOW,
        latch: Boolean = false,
        queue: Int = QUEUE_1,
    ) = doConfigureComparator(mode, polarity, latch, queue)

    /** Set `COMP_QUE` to 11; `ALERT/RDY` becomes high-impedance. */
    fun disableComparator() = doDisableComparator()

    /** Write `LO_THRESH` (signed code, same scale as `CONVERSION`). */
    fun setLowThresholdRaw(raw: Int) = doSetLowThresholdRaw(raw)

    /** Write `HI_THRESH` (signed code, same scale as `CONVERSION`). */
    fun setHighThresholdRaw(raw: Int) = doSetHighThresholdRaw(raw)

    /**
     * Write both thresholds from volts at the current gain.
     *
     * @throws IllegalArgumentException if `high <= low`
     */
    fun setThresholds(low: Double, high: Double) = doSetThresholds(low, high)

    /** `[low, high]` read back and sign-extended. */
    fun getThresholdsRaw(): IntArray = doGetThresholdsRaw()

    /** Read `CONVERSION` to release a latched comparator output. */
    fun clearAlert() = doClearAlert()

    /** Route one source ([SOURCE_CONVERSION_READY] / [SOURCE_COMPARATOR]) to `ALERT/RDY`; the other is disabled. */
    fun enableInterrupt(src: Int) = doEnableInterrupt(src)

    /** Release `ALERT/RDY` (`COMP_QUE` ← 11). */
    fun disableInterrupt(src: Int) = doDisableComparator()

    /**
     * The enabled source constant if `ALERT/RDY` is asserted, else 0. Samples `connection.intPin()`
     * when wired; without a pin [SOURCE_CONVERSION_READY] falls back to `CONFIG.OS`
     * (single-shot only). A latched alert is cleared by reading `CONVERSION`.
     */
    fun pollInterrupt(): Int = doPollInterrupt()

    /**
     * Subscribe to `ALERT/RDY` events using `connection.intPin()` (or [intPin] when given, or a
     * 5 ms polling thread if none is wired). The callback receives the [pollInterrupt] value.
     */
    fun onInterrupt(callback: (Int) -> Unit, intPin: InputPin? = connection.intPin()) = doOnInterrupt(callback, intPin)

    /** Unsubscribe and stop delivery. */
    fun offInterrupt() = doOffInterrupt()
}
