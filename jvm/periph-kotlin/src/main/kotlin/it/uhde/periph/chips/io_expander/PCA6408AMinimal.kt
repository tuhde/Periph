package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.RegisterConnection

/**
 * PCA6408A 8-bit I/O expander (I²C / SMBus) — minimal interface.
 *
 * Exposes the eight bidirectional pins P0–P7 via [pin]. CONFIG bit = 1 means
 * input, 0 means output. The chip has no internal pull-ups and no register
 * auto-increment: every register is accessed one byte at a time. Shadows of
 * OUTPUT and CONFIG make a single-pin change one register write; a pin's OUTPUT
 * bit is written before its CONFIG bit is cleared so the pin never glitches to a
 * stale level. Address `0x20` (ADDR = VSS) or `0x21` (ADDR = VDD(P)).
 *
 * @param connection I²C or SMBus (no PEC) register connection bound to the device address
 * @param addr       7-bit I²C address (`0x20` or `0x21`, default `0x20`); informational
 */
open class PCA6408AMinimal(protected val connection: RegisterConnection, val addr: Int = 0x20) {

    /** Shadow of OUTPUT. */
    var shadow = 0xFF
        internal set

    /** Shadow of CONFIG (1 = input). */
    var direction = 0xFF
        internal set

    /** Shadow of POLARITY. */
    protected var polarity = 0x00

    /** Last INPUT value read (interrupt reference). */
    protected var lastInput = 0x00

    companion object {
        internal const val REG_INPUT    = 0x00
        internal const val REG_OUTPUT   = 0x01
        internal const val REG_POLARITY = 0x02
        internal const val REG_CONFIG   = 0x03
    }

    init {
        writeReg(REG_OUTPUT, 0xFF)
        writeReg(REG_POLARITY, 0x00)
        writeReg(REG_CONFIG, 0xFF)
        lastInput = readReg(REG_INPUT)
    }

    internal fun readReg(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    internal fun writeReg(reg: Int, value: Int) = connection.write(reg, byteArrayOf(value.toByte()))

    /**
     * Read all 8 pins as a bitmask (INPUT register, actual pin levels).
     *
     * @param port ignored; the chip has a single port
     * @return 8-bit bitmask (bit 0 = P0)
     */
    fun readPort(port: Int = 0): Int = readReg(REG_INPUT)

    /**
     * Write all 8 output bits via OUTPUT and update the shadow. Bits of input
     * pins are stored but have no effect until the pin becomes an output.
     *
     * @param port ignored; the chip has a single port
     * @param mask 8-bit output mask; bit n = 1 drives Pn high
     */
    fun writePort(port: Int, mask: Int) {
        shadow = mask and 0xFF
        writeReg(REG_OUTPUT, shadow)
    }

    /**
     * Write all 8 output bits via OUTPUT.
     *
     * @param mask 8-bit output mask
     */
    fun writePort(mask: Int) = writePort(0, mask)

    /**
     * Return a [Pin] proxy for pin [n] (0–7).
     *
     * @param n pin index (0–7)
     * @return Pin proxy backed by this driver
     */
    open fun pin(n: Int): Pin = Pin(this, n and 7)

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
     * GPIO proxy for a single pin. Obtain via [PCA6408AMinimal.pin]. Implements
     * [OutputPin] so an output-configured pin can be used directly as another
     * chip's EN pin.
     */
    open class Pin(protected val chip: PCA6408AMinimal, protected val n: Int) : OutputPin {

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
