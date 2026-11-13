package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.RegisterConnection

/**
 * PCA9539 16-bit I/O expander (I²C / SMBus) — minimal interface.
 *
 * Exposes the sixteen bidirectional pins (0–7 = P00–P07, 8–15 = P10–P17) via
 * [pin]. CONFIG bit = 1 means input, 0 means output. The chip has no internal
 * pull-ups. The registers form pairs (port 0 / port 1): a two-byte transfer
 * starting at a port-0 register covers both ports. 16-bit shadows of OUTPUT and
 * CONFIG make a single-pin change one register write; a pin's OUTPUT
 * bit is written before its CONFIG bit is cleared so the pin never glitches to a
 * stale level. Address `0x74` (A1 = A0 = GND) to `0x77` (A1 = A0 = VCC).
 *
 * @param connection I²C or SMBus (no PEC) register connection bound to the device address
 * @param addr       7-bit I²C address (`0x74`–`0x77`, default `0x74`); informational
 */
open class PCA9539Minimal(protected val connection: RegisterConnection, val addr: Int = 0x74) {

    /** Shadow of OUTPUT0/1 (16 bits). */
    var shadow = 0xFFFF
        internal set

    /** Shadow of CONFIG0/1 (1 = input, 16 bits). */
    var direction = 0xFFFF
        internal set

    /** Shadow of POLARITY0/1. */
    protected var polarity = 0x0000

    /** Last INPUT0/1 value read (interrupt reference). */
    protected var lastInput = 0x0000

    companion object {
        // Port-0 register addresses; port 1 is +1. A two-byte transfer at a
        // port-0 register toggles to port 1 (register-pair layout).
        internal const val REG_INPUT    = 0x00
        internal const val REG_OUTPUT   = 0x02
        internal const val REG_POLARITY = 0x04
        internal const val REG_CONFIG   = 0x06
    }

    init {
        write16(REG_OUTPUT, 0xFFFF)
        write16(REG_POLARITY, 0x0000)
        write16(REG_CONFIG, 0xFFFF)
        lastInput = read16(REG_INPUT)
    }

    internal fun readReg(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    internal fun writeReg(reg: Int, value: Int) = connection.write(reg, byteArrayOf(value.toByte()))

    // One two-byte read at the port-0 register returns port 0 then port 1.
    internal fun read16(base: Int): Int {
        val b = connection.read(base, 2)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    internal fun write16(base: Int, value: Int) =
        connection.write(base, byteArrayOf(value.toByte(), (value shr 8).toByte()))

    /**
     * Read one 8-pin port as a bitmask (INPUTx register, actual pin levels).
     *
     * @param port 0 (P00–P07) or 1 (P10–P17)
     * @return 8-bit bitmask (bit 0 = first pin of the port)
     * @throws IllegalArgumentException if port is not 0 or 1
     */
    fun readPort(port: Int = 0): Int {
        require(port == 0 || port == 1) { "port must be 0 or 1" }
        return readReg(REG_INPUT + port)
    }

    /**
     * Write all 8 output bits of one port via OUTPUTx and update the shadow. Bits
     * of input pins are stored but have no effect until the pin becomes an output.
     *
     * @param port 0 (P00–P07) or 1 (P10–P17)
     * @param mask 8-bit output mask; bit n = 1 drives the port's pin n high
     * @throws IllegalArgumentException if port is not 0 or 1
     */
    fun writePort(port: Int, mask: Int) {
        require(port == 0 || port == 1) { "port must be 0 or 1" }
        val m = mask and 0xFF
        shadow = (shadow and (0xFF shl (8 * port)).inv()) or (m shl (8 * port))
        writeReg(REG_OUTPUT + port, m)
    }

    /**
     * Return a [Pin] proxy for pin [n] (0–15).
     *
     * @param n pin index (0–7 = port 0, 8–15 = port 1)
     * @return Pin proxy backed by this driver
     */
    open fun pin(n: Int): Pin = Pin(this, n and 15)

    internal fun setPin(n: Int, high: Boolean) {
        shadow = if (high) shadow or (1 shl n) else shadow and (1 shl n).inv()
        val port = n shr 3
        writeReg(REG_OUTPUT + port, shadow shr (8 * port))
    }

    internal fun setDirection(n: Int, input: Boolean) {
        val port = n shr 3
        if (input) {
            direction = direction or (1 shl n)
        } else {
            writeReg(REG_OUTPUT + port, shadow shr (8 * port))   // latch the output level first
            direction = direction and (1 shl n).inv()
        }
        writeReg(REG_CONFIG + port, direction shr (8 * port))
    }

    // =========================================================================
    // Pin — GPIO proxy for a single pin
    // =========================================================================

    /**
     * GPIO proxy for a single pin. Obtain via [PCA9539Minimal.pin]. Implements
     * [OutputPin] so an output-configured pin can be used directly as another
     * chip's EN pin.
     */
    open class Pin(protected val chip: PCA9539Minimal, protected val n: Int) : OutputPin {

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
        fun read(): Boolean = ((chip.readPort(n shr 3) shr (n and 7)) and 1) == 1

        /** Invert the output latch bit for this pin. */
        fun toggle() = chip.setPin(n, ((chip.shadow shr n) and 1) == 0)

        /** [OutputPin] contract: drive the pin high or low. */
        override fun set(high: Boolean) = chip.setPin(n, high)

        /** No-op; the shadow state is preserved. */
        override fun close() {}
    }
}
