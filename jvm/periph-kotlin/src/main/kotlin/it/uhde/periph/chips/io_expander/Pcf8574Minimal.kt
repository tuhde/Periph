package it.uhde.periph.chips.io_expander

import it.uhde.periph.transport.Transport

/**
 * PCF8574 8-bit quasi-bidirectional I/O port expander — minimal interface.
 *
 * Exposes all eight pins (P0–P7) as GPIO objects via the [pin] factory.
 * Direction is implicit: writing 1 puts a pin in input mode (weak ~100 µA pull-up);
 * writing 0 drives it strongly low (up to 25 mA sink). A shadow register tracks the
 * output latch for bit-level operations without a read-modify-write bus transaction.
 *
 * Initialises all pins to input mode (shadow = `0xFF`) at construction.
 *
 * Two address-range variants share identical behaviour:
 * - **PCF8574** — default address `0x20` (A2=A1=A0=0)
 * - **PCF8574A** — default address `0x38` (A2=A1=A0=0); overlaps common OLED range
 *
 * @param transport I²C transport bound to the PCF8574 device address (100 kHz max)
 */
open class Pcf8574Minimal(protected val transport: Transport) {

    /** Output latch shadow — bit n = last value written to pin n. */
    protected var shadow: Int = 0xFF

    init {
        writePort(mask = 0xFF)
    }

    // -------------------------------------------------------------------------
    // Port-level API
    // -------------------------------------------------------------------------

    /**
     * Read all 8 pins as a bitmask.
     *
     * Returns the actual logic level at each pin regardless of the shadow register.
     * Bit 0 = P0, bit 7 = P7.
     *
     * @param port port index (ignored; the PCF8574 has exactly one port)
     * @return 8-bit bitmask of current pin states
     */
    fun readPort(port: Int = 0): Int = transport.read(1)[0].toInt() and 0xFF

    /**
     * Write all 8 pins at once and update the shadow register.
     *
     * @param mask 8-bit output mask; 1 = input mode (weak pull-up), 0 = drive low
     */
    fun writePort(mask: Int) {
        shadow = mask and 0xFF
        transport.write(byteArrayOf(shadow.toByte()))
    }

    /**
     * Write all 8 pins at once and update the shadow register.
     *
     * @param port port index (ignored; the PCF8574 has exactly one port)
     * @param mask 8-bit output mask; 1 = input mode (weak pull-up), 0 = drive low
     */
    fun writePort(port: Int, mask: Int) = writePort(mask)

    // -------------------------------------------------------------------------
    // Pin factory
    // -------------------------------------------------------------------------

    /**
     * Return a [Pin] proxy for pin [n] (0–7).
     *
     * @param n pin index (0 = P0, 7 = P7)
     * @return Pin proxy backed by this driver's shadow register
     */
    fun pin(n: Int): Pin = Pin(this, n)

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Set or clear a single pin and update the shadow register.
     *
     * @param n    pin index (0–7)
     * @param high `true` to release to input mode (write 1);
     *             `false` to drive low (write 0)
     */
    internal fun setPin(n: Int, high: Boolean) {
        shadow = if (high) shadow or (1 shl n) else shadow and (1 shl n).inv()
        shadow = shadow and 0xFF
        transport.write(byteArrayOf(shadow.toByte()))
    }

    // =========================================================================
    // Pin — GPIO proxy for a single PCF8574 pin
    // =========================================================================

    /**
     * GPIO proxy for a single PCF8574 pin.
     *
     * Obtain via [Pcf8574Minimal.pin]. Do not instantiate directly.
     *
     * Direction is implicit: [setHigh] releases the pin to quasi-input (internal
     * ~100 µA pull-up); [setLow] drives the pin low via the 25 mA open-drain sink.
     * [setInput] and [setOutput] are aliases for [setHigh] and [setLow] respectively.
     */
    open class Pin(protected val chip: Pcf8574Minimal, protected val n: Int) {

        /** Release the pin to input mode by writing 1 to the shadow bit. */
        fun setInput() = chip.setPin(n, true)

        /**
         * Drive the pin low by writing 0 to the shadow bit.
         *
         * The PCF8574 cannot actively drive high; call [setHigh] to release after output.
         */
        fun setOutput() = chip.setPin(n, false)

        /** Write 1 to this pin — releases it to quasi-input mode (internal pull-up). */
        fun setHigh() = chip.setPin(n, true)

        /** Write 0 to this pin — drives it low (open-drain sink, up to 25 mA). */
        fun setLow() = chip.setPin(n, false)

        /**
         * Read the actual logic level at the pin.
         *
         * Performs a bus read; the result reflects the external signal.
         *
         * @return `true` if the pin is high; `false` if low
         */
        fun read(): Boolean =
            ((chip.transport.read(1)[0].toInt() and 0xFF) shr n and 1) == 1

        /**
         * Invert the shadow bit for this pin.
         *
         * If the last written value was 1 the pin is driven low; if 0, released to input.
         */
        fun toggle() = chip.setPin(n, (chip.shadow shr n and 1) == 0)
    }
}
