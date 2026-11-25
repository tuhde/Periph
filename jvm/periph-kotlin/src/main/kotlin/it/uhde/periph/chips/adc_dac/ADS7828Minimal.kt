package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection

/**
 * ADS7828 — 12-bit, 8-channel sampling ADC with internal 2.5 V reference
 * (TI / Burr-Brown) — minimal interface.
 *
 * The chip has no registers: one command byte selects the input mode
 * (single-ended or differential), the channel and the power-down state and
 * starts a conversion; the 12-bit result is read back as two bytes.
 * `connection.read(command, 2)` issues exactly the datasheet's write-command /
 * repeated-START / read-two-bytes sequence. The I²C address is 0x48–0x4B.
 *
 * Reads the eight single-ended inputs (CHn against COM) as raw codes or
 * voltages. Defaults to the internal 2.5 V reference (LSB 610 µV), kept on
 * between conversions. Construction performs no bus traffic; [init] (or the
 * first conversion) enables the internal reference and waits 1 ms.
 *
 * @param connection configured I²C connection bound to the device (0x48–0x4B)
 * @param vref external reference voltage in volts (0.05 V … VDD), or `null` for the internal 2.5 V reference
 */
open class ADS7828Minimal(
    protected val connection: RegisterConnection,
    vref: Double? = null,
) {

    companion object {
        /** Default 7-bit I²C address (A1 = A0 = GND). */
        const val DEFAULT_ADDRESS = 0x48

        const val INTERNAL_VREF = 2.5
        const val PD_ALL_DOWN = 0
        const val PD_REF_ON_ADC_ON = 3

        /** Lone command: SD = 0, C = 0, PD = 10 (reference on, ADC off). */
        const val CMD_REF_ON = 0x08

        /** Lone command: SD = 0, C = 0, PD = 00 (full power-down). */
        const val CMD_POWER_DOWN = 0x00
        const val REF_SETTLE_MS = 1L
    }

    protected var internalRef: Boolean = true
    protected var refOn: Boolean = false
    protected var vref: Double = INTERNAL_VREF
    protected var pd: Int = PD_REF_ON_ADC_ON

    init {
        selectReference(vref)
    }

    /** Select the reference; `null` = internal 2.5 V. Does not touch the bus. */
    protected fun selectReference(reference: Double?) {
        if (reference == null) {
            internalRef = true
            vref = INTERNAL_VREF
            pd = PD_REF_ON_ADC_ON
        } else {
            internalRef = false
            vref = reference
            pd = PD_ALL_DOWN
        }
        refOn = false
    }

    protected fun enableReference() {
        connection.write(byteArrayOf(CMD_REF_ON.toByte()))
        try {
            Thread.sleep(REF_SETTLE_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        refOn = true
    }

    /**
     * Enable the internal reference now (no-op with an external one): sends the
     * lone command 0x08 and waits 1 ms. Optional — the first conversion does
     * this automatically.
     */
    fun init() {
        if (internalRef) enableReference()
    }

    protected fun convert(singleEnded: Boolean, code: Int): Int {
        if (internalRef && !refOn) enableReference()
        val command = ((if (singleEnded) 1 else 0) shl 7) or (code shl 4) or (pd shl 2)
        val data = connection.read(command, 2)
        return ((data[0].toInt() and 0x0F) shl 8) or (data[1].toInt() and 0xFF)
    }

    /**
     * Read a single-ended channel (CHn against COM) as a 12-bit code.
     *
     * @param channel channel number 0–7 (clamped)
     * @return raw code 0–4095
     */
    fun readRaw(channel: Int): Int {
        val ch = maxOf(0, minOf(7, channel))
        return convert(true, ((ch and 1) shl 2) or (ch shr 1))
    }

    /**
     * Read a single-ended channel as a voltage.
     *
     * @param channel channel number 0–7 (clamped)
     * @return input voltage in volts (`raw / 4096 × vref`)
     */
    fun readVoltage(channel: Int): Double = readRaw(channel) / 4096.0 * vref

    /**
     * Read all eight single-ended channels, one conversion each.
     *
     * @return voltages of CH0…CH7 in volts
     */
    fun readAll(): DoubleArray = DoubleArray(8) { readVoltage(it) }
}
