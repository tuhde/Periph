package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * ADS7828 full interface — extends {@link ADS7828Minimal} with differential
 * reads (both polarities), a signed differential read, run-time reference
 * switching, power-down control and multi-channel scans.
 */
@CompileStatic
class ADS7828Full extends ADS7828Minimal {

    /** PD = 00: power down between conversions (internal reference off). */
    public static final int POWER_DOWN_BETWEEN = 0
    /** PD = 01: internal reference off, ADC on. */
    public static final int REF_OFF_ADC_ON = 1
    /** PD = 10: internal reference on, ADC off. */
    public static final int REF_ON_ADC_OFF = 2
    /** PD = 11: internal reference on, ADC on. */
    public static final int REF_ON_ADC_ON = 3

    /**
     * Construct the driver with the internal 2.5 V reference. Performs no bus traffic.
     *
     * @param connection configured I²C connection bound to the device (0x48–0x4B)
     */
    public ADS7828Full(RegisterConnection connection) {
        super(connection)
    }

    /**
     * Construct the driver with an external reference. Performs no bus traffic.
     *
     * @param connection configured I²C connection bound to the device (0x48–0x4B)
     * @param vref       external reference voltage in volts
     */
    public ADS7828Full(RegisterConnection connection, double vref) {
        super(connection, vref)
    }

    /**
     * Read a differential pair as a 12-bit code.
     *
     * @param pair     pair index 0–3 (CH0/CH1 … CH6/CH7, clamped)
     * @param inverted if true the positive input is CH(2·pair+1)
     * @return raw code 0–4095; a negative differential voltage reads 0
     */
    public int readDifferentialRaw(int pair, boolean inverted) {
        int p = Math.max(0, Math.min(3, pair))
        return convert(false, inverted ? (4 | p) : p)
    }

    /**
     * Read a differential pair as a voltage.
     *
     * @param pair     pair index 0–3 (clamped)
     * @param inverted if true the positive input is CH(2·pair+1)
     * @return differential voltage in volts (≥ 0)
     */
    public double readDifferential(int pair, boolean inverted) {
        return readDifferentialRaw(pair, inverted) / 4096.0 * vref
    }

    /**
     * Read V(CH2p) − V(CH2p+1) with sign, using two conversions (normal and
     * inverted polarity).
     *
     * @param pair pair index 0–3 (clamped)
     * @return signed differential voltage in volts (−vref … +vref)
     */
    public double readSignedDifferential(int pair) {
        int normal = readDifferentialRaw(pair, false)
        int inverted = readDifferentialRaw(pair, true)
        return (normal - inverted) / 4096.0 * vref
    }

    /**
     * Switch between the internal and an external reference.
     *
     * @param reference {@code null} for the internal 2.5 V reference (re-runs
     *                  the turn-on and settle wait), or an external reference
     *                  voltage in volts (PD1 = 0 from then on)
     */
    public void setReference(Double reference) {
        selectReference(reference)
        init()
    }

    /**
     * Reference voltage used for scaling.
     *
     * @return volts (2.5 for the internal reference)
     */
    public double getReference() {
        return vref
    }

    /**
     * Whether the internal reference is selected.
     *
     * @return true for the internal reference
     */
    public boolean usesInternalReference() {
        return internal
    }

    /**
     * Set the PD bits used in all following conversion commands.
     *
     * @param pd {@link #POWER_DOWN_BETWEEN}, {@link #REF_OFF_ADC_ON},
     *           {@link #REF_ON_ADC_OFF} or {@link #REF_ON_ADC_ON}
     * @throws IllegalArgumentException if pd is outside 0–3, or if the internal
     *                                  reference is selected and pd has PD1 = 0
     */
    public void setPowerMode(int pd) {
        if (pd < 0 || pd > 3) throw new IllegalArgumentException("pd must be 0-3")
        if (internal && (pd & 2) == 0) {
            throw new IllegalArgumentException("internal reference requires PD1 = 1")
        }
        this.pd = pd
    }

    /**
     * Power the chip down completely (≈ 400 nA) with a lone command (PD = 00).
     * With the internal reference this turns it off; the next read re-runs the
     * turn-on and settle wait.
     *
     */
    public void powerDown() {
        connection.write([(byte) CMD_POWER_DOWN] as byte[])
        refOn = false
    }

    /**
     * Read several single-ended channels in order as raw codes.
     *
     * @param channels channel numbers 0–7
     * @return raw 12-bit codes, one conversion per channel
     */
    public int[] readScan(int[] channels) {
        int[] out = new int[channels.length]
        for (int i = 0; i < channels.length; i++) out[i] = readRaw(channels[i])
        return out
    }

    /**
     * Size of one code step.
     *
     * @return {@code vref / 4096} in volts (610 µV at 2.5 V)
     */
    public double lsb() {
        return vref / 4096.0
    }
}
