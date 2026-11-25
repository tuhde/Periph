package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.RegisterConnection

/**
 * PCA9536 4-bit I/O expander (I²C / SMBus) — minimal interface.
 *
 * Exposes the four bidirectional pins P0–P3 via [pin]. CONFIG bit = 1 means
 * input, 0 means output. Every I/O pin has a weak (about 100 kΩ) pull-up that
 * is always on and cannot be switched (an undriven input reads 1); the chip has
 * no register auto-increment: every register is accessed one byte at a time and
 * bits 7:4 of every register are unused. Shadows of OUTPUT and CONFIG make a
 * single-pin change one register write; a pin's OUTPUT bit is written before its
 * CONFIG bit is cleared so the pin never glitches to a stale level. The address
 * is fixed at `0x41`; there is no interrupt, reset or address pin.
 *
 * @param connection I²C or SMBus (no PEC) register connection bound to the device address
 * @param addr       7-bit I²C address (fixed `0x41`); informational
 */
open class PCA9536Minimal(protected val connection: RegisterConnection, val addr: Int = 0x41) {

    /** Shadow of OUTPUT. */
    var shadow = 0xFF
        internal set

    /** Shadow of CONFIG (1 = input). */
    var direction = 0xFF
        internal set

    /** Shadow of POLARITY. */
    protected var polarity = 0x00

    /** Last INPUT value read (change-detection reference). */
    protected var lastInput = 0x00

    companion object {
        internal const val REG_INPUT    = 0x00
        internal const val REG_OUTPUT   = 0x01
        internal const val REG_POLARITY = 0x02
        internal const val REG_CONFIG   = 0x03

        /** Bits 7:4 are unused. */
        internal const val MASK = 0x0F

        /** Fixed 7-bit I²C address. */
        const val DEFAULT_ADDRESS = 0x41
    }

    init {
        writeReg(REG_OUTPUT, 0xFF)
        writeReg(REG_POLARITY, 0x00)
        writeReg(REG_CONFIG, 0xFF)
        lastInput = readReg(REG_INPUT) and MASK
    }

    internal fun readReg(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    internal fun writeReg(reg: Int, value: Int) = connection.write(reg, byteArrayOf(value.toByte()))

    /**
     * Read all 4 pins as a bitmask (INPUT register, actual pin levels, bits 7:4 masked off).
     *
     * @param port ignored; the chip has a single port
     * @return 4-bit bitmask (bit 0 = P0)
     */
    fun readPort(port: Int = 0): Int = readReg(REG_INPUT) and MASK

    /**
     * Write all 4 output bits via OUTPUT (bits 7:4 are written as 1) and update the shadow. Bits of input
     * pins are stored but have no effect until the pin becomes an output.
     *
     * @param port ignored; the chip has a single port
     * @param mask 4-bit output mask; bit n = 1 drives Pn high
     */
    fun writePort(port: Int, mask: Int) {
        shadow = (mask and MASK) or 0xF0
        writeReg(REG_OUTPUT, shadow)
    }

    /**
     * Write all 4 output bits via OUTPUT.
     *
     * @param mask 4-bit output mask
     */
    fun writePort(mask: Int) = writePort(0, mask)

    /**
     * Return a [Pin] proxy for pin [n] (0–3).
     *
     * @param n pin index (0–3)
     * @return Pin proxy backed by this driver
     */
    open fun pin(n: Int): Pin = Pin(this, n and 3)

    internal fun setPin(n: Int, high: Boolean) {
        shadow = if (high) shadow or (1 shl n) else shadow and (1 shl n).inv()
        writeReg(REG_OUTPUT, shadow)
    }

    internal fun setDirection(n: Int, input: Boolean) {
        if (input) {
            direction = direction or (1 shl n)
        } else {
            writeReg(REG_OUTPUT, shadow)   // latch the output level first
            direction = direction and (1 shl n).inv()
        }
        writeReg(REG_CONFIG, direction)
    }

    // =========================================================================
    // Pin — GPIO proxy for a single pin
    // =========================================================================

    /**
     * GPIO proxy for a single pin. Obtain via [PCA9536Minimal.pin]. Implements
     * [OutputPin] so an output-configured pin can be used directly as another
     * chip's EN pin.
     */
    open class Pin(protected val chip: PCA9536Minimal, protected val n: Int) : OutputPin {

        /** Set this pin as an input (CONFIG bit = 1). */
        fun setInput() = chip.setDirection(n, true)

        /** Set this pin as an output (OUTPUT bit written first, then CONFIG bit = 0). */
        fun setOutput() = chip.setDirection(n, false)

        /** Drive the pin high. */
        fun setHigh() = chip.setPin(n, true)

        /** Drive the pin low. */
        fun setLow() = chip.setPin(n, false)

        /**
         * Read the actual logic level at the pin (a bus read of INPUT).
         *
         * @return `true` if the pin is high
         */
        fun read(): Boolean = ((chip.readPort() shr n) and 1) == 1

        /** Invert the output latch bit for this pin. */
        fun toggle() = chip.setPin(n, ((chip.shadow shr n) and 1) == 0)

        /** [OutputPin] contract: drive the pin high or low. */
        override fun set(high: Boolean) = chip.setPin(n, high)

        /** No-op; the shadow state is preserved. */
        override fun close() {}
    }
}
