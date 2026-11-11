package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection

/**
 * ADS7828 full interface — extends [ADS7828Minimal] with differential reads
 * (both polarities), a signed differential read, run-time reference
 * switching, power-down control and multi-channel scans.
 *
 * @param connection configured I²C connection bound to the device (0x48–0x4B)
 * @param vref external reference voltage in volts, or `null` for the internal 2.5 V reference
 */
class ADS7828Full(
    connection: RegisterConnection,
    vref: Double? = null,
) : ADS7828Minimal(connection, vref) {

    companion object {
        /** PD = 00: power down between conversions (internal reference off). */
        const val POWER_DOWN_BETWEEN = 0

        /** PD = 01: internal reference off, ADC on. */
        const val REF_OFF_ADC_ON = 1

        /** PD = 10: internal reference on, ADC off. */
        const val REF_ON_ADC_OFF = 2

        /** PD = 11: internal reference on, ADC on. */
        const val REF_ON_ADC_ON = 3
    }

    /**
     * Read a differential pair as a 12-bit code.
     *
     * @param pair pair index 0–3 (CH0/CH1 … CH6/CH7, clamped)
     * @param inverted if true the positive input is CH(2·pair+1)
     * @return raw code 0–4095; a negative differential voltage reads 0
     */
    fun readDifferentialRaw(pair: Int, inverted: Boolean = false): Int {
        val p = maxOf(0, minOf(3, pair))
        return convert(false, if (inverted) (4 or p) else p)
    }

    /**
     * Read a differential pair as a voltage.
     *
     * @param pair pair index 0–3 (clamped)
     * @param inverted if true the positive input is CH(2·pair+1)
     * @return differential voltage in volts (≥ 0)
     */
    fun readDifferential(pair: Int, inverted: Boolean = false): Double =
        readDifferentialRaw(pair, inverted) / 4096.0 * vref

    /**
     * Read V(CH2p) − V(CH2p+1) with sign, using two conversions (normal and
     * inverted polarity).
     *
     * @param pair pair index 0–3 (clamped)
     * @return signed differential voltage in volts (−vref … +vref)
     */
    fun readSignedDifferential(pair: Int): Double {
        val normal = readDifferentialRaw(pair, false)
        val inverted = readDifferentialRaw(pair, true)
        return (normal - inverted) / 4096.0 * vref
    }

    /**
     * Switch between the internal and an external reference.
     *
     * @param reference `null` for the internal 2.5 V reference (re-runs the
     *   turn-on and settle wait), or an external reference voltage in volts
     *   (PD1 = 0 from then on)
     */
    fun setReference(reference: Double?) {
        selectReference(reference)
        init()
    }

    /** Reference voltage used for scaling, in volts (2.5 for the internal reference). */
    fun getReference(): Double = vref

    /** Whether the internal reference is selected. */
    fun usesInternalReference(): Boolean = internalRef

    /**
     * Set the PD bits used in all following conversion commands.
     *
     * @param pd [POWER_DOWN_BETWEEN], [REF_OFF_ADC_ON], [REF_ON_ADC_OFF] or [REF_ON_ADC_ON]
     * @throws IllegalArgumentException if pd is outside 0–3, or if the internal
     *   reference is selected and pd has PD1 = 0
     */
    fun setPowerMode(pd: Int) {
        require(pd in 0..3) { "pd must be 0-3" }
        require(!internalRef || (pd and 2) != 0) { "internalRef reference requires PD1 = 1" }
        this.pd = pd
    }

    /**
     * Power the chip down completely (≈ 400 nA) with a lone command (PD = 00).
     * With the internal reference this turns it off; the next read re-runs the
     * turn-on and settle wait.
     */
    fun powerDown() {
        connection.write(byteArrayOf(CMD_POWER_DOWN.toByte()))
        refOn = false
    }

    /**
     * Read several single-ended channels in order as raw codes.
     *
     * @param channels channel numbers 0–7
     * @return raw 12-bit codes, one conversion per channel
     */
    fun readScan(channels: IntArray): IntArray = IntArray(channels.size) { readRaw(channels[it]) }

    /** Size of one code step: `vref / 4096` in volts (610 µV at 2.5 V). */
    fun lsb(): Double = vref / 4096.0
}
