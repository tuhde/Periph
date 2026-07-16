package it.uhde.periph.chips.adc_dac

import it.uhde.periph.transport.Transport

/**
 * PCF8591 — full driver. Extends [Pcf8591Minimal] with analog input
 * mode selection, auto-increment, DAC enable/disable, raw and
 * voltage-calibrated ADC reads, and signed differential reads.
 */
class Pcf8591Full(transport: Transport) : Pcf8591Minimal(transport) {

    /** 4 single-ended inputs (AIN0–AIN3). */
    val inputMode: Int
        get() = _inputMode

    /** Whether the DAC output is enabled. */
    val isDacEnabled: Boolean
        get() = _dacEnabled

    /** Whether auto-increment is enabled. */
    val isAutoIncrement: Boolean
        get() = _autoIncrement

    private var _control: Int = control
    private var _inputMode: Int = MODE_4_SINGLE_ENDED
    private var _dacEnabled: Boolean = false
    private var _autoIncrement: Boolean = false
    private var lastChannel: Int = 0

    /**
     * Set the analog input mode, auto-increment, and DAC enable.
     *
     * @param inputMode     analog input programming 0–3 (see MODE_* constants)
     * @param autoIncrement if true, AI=1 — channel increments after each conversion
     * @param dacEnabled    if true, AOE=1 — AOUT is active; AOUT returns to
     *                      high-impedance when false
     */
    fun configure(inputMode: Int, autoIncrement: Boolean, dacEnabled: Boolean) {
        val aip = inputMode and 0x03
        val ai  = if (autoIncrement) 0x04 else 0x00
        val aoe = if (dacEnabled) 0x40 else 0x00
        _control = (aip shl 4) or aoe or ai or (lastChannel and 0x03)
        _inputMode     = aip
        _autoIncrement = autoIncrement
        _dacEnabled    = dacEnabled
        transport.write(byteArrayOf(_control.toByte()))
    }

    /**
     * Read a single channel and convert to voltage.
     *
     * @param channel channel number 0–3
     * @param vref    reference voltage in volts
     * @param vagnd   analog ground voltage in volts
     * @return channel voltage in volts
     */
    fun readChannelVoltage(channel: Int, vref: Double, vagnd: Double): Double {
        val raw = readChannel(channel)
        return vagnd + raw * (vref - vagnd) / 256.0
    }

    /**
     * Read all four channels and convert each to voltage.
     *
     * @param vref  reference voltage in volts
     * @param vagnd analog ground voltage in volts
     * @return four channel voltages [ch0, ch1, ch2, ch3]
     */
    fun readAllVoltage(vref: Double, vagnd: Double): DoubleArray {
        val raws = readAll()
        val vfs = vref - vagnd
        return DoubleArray(4) { i -> vagnd + raws[i] * vfs / 256.0 }
    }

    /**
     * Read a differential channel as a signed value.
     *
     * The chip must be configured in a differential mode (inputMode 1, 2,
     * or 3). The result is interpreted as a signed 8-bit two's complement
     * number.
     *
     * @param channel differential channel index (0–2 for 3-diff mode, 0–1
     *                for 2-diff and mixed modes)
     * @return signed 8-bit value (-128 to 127)
     */
    fun readDifferential(channel: Int): Int {
        val ch = channel and 0x03
        lastChannel = ch
        val ctrl = _control or (ch and 0x03)
        transport.write(byteArrayOf(ctrl.toByte()))
        val buf = transport.read(2)
        val raw = buf[1].toInt() and 0xFF
        return if (raw >= 128) raw - 256 else raw
    }

    /**
     * Enable the DAC and write a raw 8-bit value.
     *
     * Sets the AOE bit so AOUT becomes active, then writes the DAC value
     * in the byte following the control byte.
     *
     * @param value raw 8-bit DAC value (0–255). Output voltage is
     *              V_AGND + value × (V_REF − V_AGND) / 256.
     */
    fun setDac(value: Int) {
        val v = value.coerceIn(0, 255)
        _control = (_control or 0x40) and 0x04.inv()  // AOE=1, AI=0
        _dacEnabled = true
        transport.write(byteArrayOf(_control.toByte(), v.toByte()))
    }

    /**
     * Enable the DAC and set the output as a fraction of (VREF−VAGND).
     *
     * @param voltageFraction output level as a fraction of (VREF−VAGND)
     *                        (0.0 = V_AGND, 1.0 = V_REF). Clamped to [0.0, 1.0].
     */
    fun setDacVoltage(voltageFraction: Double) {
        val f = voltageFraction.coerceIn(0.0, 1.0)
        val value = (f * 255).toInt()
        setDac(value)
    }

    /**
     * Disable the DAC output; AOUT returns to high-impedance.
     */
    fun disableDac() {
        _control = _control and 0x40.inv()  // AOE=0
        _dacEnabled = false
        transport.write(byteArrayOf(_control.toByte()))
    }

    companion object {
        /** 4 single-ended inputs (AIN0–AIN3). */
        const val MODE_4_SINGLE_ENDED  = 0
        /** 3 differential inputs (vs AIN3). */
        const val MODE_3_DIFFERENTIAL = 1
        /** AIN0/1 single-ended, AIN2-AIN3 differential. */
        const val MODE_MIXED          = 2
        /** 2 differential inputs. */
        const val MODE_2_DIFFERENTIAL = 3
    }
}
