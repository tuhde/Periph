package it.uhde.periph.chips.io_expander

import it.uhde.periph.transport.Transport

/**
 * MCP23017 16-bit bidirectional I/O port expander — minimal interface.
 *
 * Provides 16 GPIO pins split into two 8-bit ports (PORTA: GPA0–GPA7,
 * PORTB: GPB0–GPB7). Pins are accessed via the [pin] factory.
 * A 2-element shadow array tracks OLATA/OLATB for bit-level read-modify-write
 * without an I²C read.
 *
 * At construction, all pins initialise as inputs except GPA7 and GPB7
 * which are output-only on the hardware and are forced to output mode.
 * OLATA and OLATB are cleared to `0x00`.
 *
 * IOCON.BANK is left at 0 (power-on default) throughout.
 *
 * @param transport I²C transport bound to the device address
 * @param addr      7-bit I²C address (`0x20`–`0x27`, default `0x20`)
 */
open class Mcp23017Minimal(protected val transport: Transport, val addr: Int = 0x20) {

    /** Output latch shadow. shadow[0] = OLATA, shadow[1] = OLATB. */
    protected val shadow = intArrayOf(0, 0)

    companion object {
        private const val REG_IODIRA = 0x00
        private const val REG_IODIRB = 0x01
        private const val REG_GPIOA  = 0x12
        private const val REG_GPIOB  = 0x13
        private const val REG_OLATA  = 0x14
        private const val REG_OLATB  = 0x15
    }

    init {
        writeReg(REG_OLATA, 0x00)
        writeReg(REG_OLATB, 0x00)
        writeReg(REG_IODIRA, 0x7F)
        writeReg(REG_IODIRB, 0x7F)
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun writeReg(reg: Int, value: Int) {
        transport.write(byteArrayOf(reg.toByte(), (value and 0xFF).toByte()))
    }

    private fun readReg(reg: Int): Int {
        return transport.writeRead(byteArrayOf(reg.toByte()), 1)[0].toInt() and 0xFF
    }

    // -------------------------------------------------------------------------
    // Public driver API
    // -------------------------------------------------------------------------

    /**
     * Read all 8 pins of a port as a bitmask.
     *
     * @param port 0 = PORTA (GPIOA), 1 = PORTB (GPIOB)
     * @return 8-bit bitmask (bit 0 = pin 0 of the port)
     */
    fun readPort(port: Int): Int = readReg(REG_GPIOA + port)

    /**
     * Write all 8 output pins of a port via the output latch.
     * Updates the internal shadow register.
     *
     * @param port 0 = PORTA (OLATA), 1 = PORTB (OLATB)
     * @param mask 8-bit output mask; bit n = 1 drives pin n high
     */
    fun writePort(port: Int, mask: Int) {
        shadow[port] = mask and 0xFF
        writeReg(REG_OLATA + port, shadow[port])
    }

    /**
     * Return a [Pin] proxy for pin [n] (0–15).
     *
     * Pins 0–7 map to PORTA (GPA0–GPA7); pins 8–15 map to PORTB (GPB0–GPB7).
     * GPA7 (pin 7) and GPB7 (pin 15) are output-only on the hardware.
     *
     * @param n pin index (0–15)
     * @return Pin proxy backed by this driver
     */
    fun pin(n: Int): Pin = Pin(this, n)

    // -------------------------------------------------------------------------
    // Internal pin operations
    // -------------------------------------------------------------------------

    internal fun setPin(n: Int, high: Boolean) {
        val port = n shr 3
        val bit  = n and 7
        shadow[port] = if (high) shadow[port] or   (1 shl bit) else shadow[port] and (1 shl bit).inv()
        writeReg(REG_OLATA + port, shadow[port])
    }

    internal fun readPin(n: Int): Int {
        val port = n shr 3
        return (readPort(port) shr (n and 7)) and 1
    }

    // =========================================================================
    // Pin — GPIO proxy for a single MCP23017 pin
    // =========================================================================

    /**
     * GPIO proxy for a single MCP23017 pin.
     *
     * Obtain via [Mcp23017Minimal.pin]. Do not instantiate directly.
     */
    open class Pin(protected val chip: Mcp23017Minimal, protected val n: Int) {

        /**
         * Set this pin as an input (IODIR bit = 1).
         *
         * GPA7 and GPB7 ignore this call — they are always outputs.
         */
        fun setInput() {
            val port = n shr 3
            val bit  = n and 7
            val reg  = REG_IODIRA + port
            val cur  = chip.readReg(reg)
            chip.writeReg(reg, cur or (1 shl bit))
        }

        /** Set this pin as an output (IODIR bit = 0). */
        fun setOutput() {
            val port = n shr 3
            val bit  = n and 7
            val reg  = REG_IODIRA + port
            val cur  = chip.readReg(reg)
            chip.writeReg(reg, cur and (1 shl bit).inv())
        }

        /** Drive the pin high. */
        fun setHigh() { chip.setPin(n, true) }

        /** Drive the pin low. */
        fun setLow()  { chip.setPin(n, false) }

        /**
         * Read the actual logic level at the pin.
         *
         * @return `true` if the pin is high; `false` if low
         */
        fun read(): Boolean = chip.readPin(n) == 1

        /** Invert the output latch bit for this pin. */
        fun toggle() {
            val port = n shr 3
            val bit  = n and 7
            val current = (chip.shadow[port] shr bit) and 1
            chip.setPin(n, current == 0)
        }
    }
}