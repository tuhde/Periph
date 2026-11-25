package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.RegisterConnection

/**
 * Shared implementation of the MCP23008 (I²C) and MCP23S08 (SPI) drivers —
 * minimal interface. Use [Mcp23008Minimal] or [Mcp23S08Minimal].
 *
 * Both chips have an identical register map and GPIO behaviour and differ only
 * in the serial interface, which is hidden behind [Bus]. All eight pins
 * (GP0–GP7) are bidirectional. Shadows of OLAT and IODIR make a single-pin
 * change one register write; a pin's OLAT bit is written before its IODIR bit
 * is cleared so the pin never glitches to a stale level.
 *
 * @param bus        register access for the chip's serial interface
 * @param connection the underlying connection (for its INT pin)
 */
open class Mcp23x08Minimal(protected val bus: Bus, protected val connection: Connection) {

    /** Register-level access to the chip over its serial interface. */
    interface Bus {
        /** IOCON value the driver keeps after init and reset (0x00 on I²C, HAEN on SPI). */
        val baseIocon: Int

        /** Bring-up before the first regular access (SPI: set IOCON.HAEN). */
        fun init() {}

        /** Read one register. */
        fun read(reg: Int): Int

        /** Write one register. */
        fun write(reg: Int, value: Int)
    }

    /** Shadow of OLAT. */
    var shadow = 0x00
        internal set

    /** Shadow of IODIR (1 = input). */
    var direction = 0xFF
        internal set

    companion object {
        internal const val REG_IODIR   = 0x00
        internal const val REG_IPOL    = 0x01
        internal const val REG_GPINTEN = 0x02
        internal const val REG_DEFVAL  = 0x03
        internal const val REG_INTCON  = 0x04
        internal const val REG_IOCON   = 0x05
        internal const val REG_GPPU    = 0x06
        internal const val REG_INTF    = 0x07
        internal const val REG_INTCAP  = 0x08
        internal const val REG_GPIO    = 0x09
        internal const val REG_OLAT    = 0x0A

        internal const val IOCON_SEQOP  = 0x20
        internal const val IOCON_HAEN   = 0x08
        internal const val IOCON_ODR    = 0x04
        internal const val IOCON_INTPOL = 0x02
    }

    init {
        bus.init()
        bus.write(REG_OLAT, 0x00)
    }

    /**
     * Read all 8 pins as a bitmask (GPIO register, actual pin levels).
     *
     * @param port ignored; the chip has a single port
     * @return 8-bit bitmask (bit 0 = GP0)
     */
    fun readPort(port: Int = 0): Int = bus.read(REG_GPIO)

    /**
     * Write all 8 output bits via OLAT and update the shadow. Bits of input
     * pins are stored but have no effect until the pin becomes an output.
     *
     * @param port ignored; the chip has a single port
     * @param mask 8-bit output mask; bit n = 1 drives GPn high
     */
    fun writePort(port: Int, mask: Int) {
        shadow = mask and 0xFF
        bus.write(REG_OLAT, shadow)
    }

    /**
     * Write all 8 output bits via OLAT.
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
        bus.write(REG_OLAT, shadow)
    }

    internal fun setDirection(n: Int, input: Boolean) {
        if (input) {
            direction = direction or (1 shl n)
        } else {
            bus.write(REG_OLAT, shadow)   // latch the output level first
            direction = direction and (1 shl n).inv()
        }
        bus.write(REG_IODIR, direction)
    }

    // =========================================================================
    // Pin — GPIO proxy for a single pin
    // =========================================================================

    /**
     * GPIO proxy for a single pin. Obtain via [Mcp23x08Minimal.pin]. Implements
     * [OutputPin] so an output-configured pin can be used directly as another
     * chip's EN pin.
     */
    open class Pin(protected val chip: Mcp23x08Minimal, protected val n: Int) : OutputPin {

        /** Set this pin as an input (IODIR bit = 1). */
        fun setInput() = chip.setDirection(n, true)

        /** Set this pin as an output (OLAT bit written first, then IODIR bit = 0). */
        fun setOutput() = chip.setDirection(n, false)

        /** Drive the pin high. */
        fun setHigh() = chip.setPin(n, true)

        /** Drive the pin low. */
        fun setLow() = chip.setPin(n, false)

        /**
         * Read the actual logic level at the pin (a bus read of GPIO).
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

/** I²C transport: 1-byte register address through a [RegisterConnection]. */
internal class Mcp23008I2cBus(private val conn: RegisterConnection) : Mcp23x08Minimal.Bus {
    override val baseIocon: Int get() = 0x00
    override fun read(reg: Int): Int = conn.read(reg, 1)[0].toInt() and 0xFF
    override fun write(reg: Int, value: Int) = conn.write(reg, byteArrayOf(value.toByte()))
}

/**
 * SPI transport: `[0x40 | (addr << 1) | R/W] [reg] [data]` over a plain
 * [Connection].
 */
internal class Mcp23S08SpiBus(private val conn: Connection, addr: Int) : Mcp23x08Minimal.Bus {
    private val addr = addr and 3
    override val baseIocon: Int get() = Mcp23x08Minimal.IOCON_HAEN

    override fun init() {
        // HAEN resets to 0, so an un-addressed device decodes address 00: the
        // first IOCON write goes out under the address-0 opcode.
        conn.write(byteArrayOf(0x40, Mcp23x08Minimal.REG_IOCON.toByte(), Mcp23x08Minimal.IOCON_HAEN.toByte()))
    }

    override fun read(reg: Int): Int =
        conn.writeRead(byteArrayOf((0x40 or (addr shl 1) or 1).toByte(), reg.toByte()), 1)[0].toInt() and 0xFF

    override fun write(reg: Int, value: Int) =
        conn.write(byteArrayOf((0x40 or (addr shl 1)).toByte(), reg.toByte(), value.toByte()))
}

/**
 * MCP23008 8-bit I/O port expander (I²C) — minimal interface.
 *
 * Exposes the eight bidirectional pins GP0–GP7 via [pin]. Address range
 * `0x20`–`0x27` (A2:A0 added to `0x20`).
 *
 * @param connection I²C register connection bound to the device address
 * @param addr       7-bit I²C address (`0x20`–`0x27`, default `0x20`)
 */
open class Mcp23008Minimal(connection: RegisterConnection, val addr: Int = 0x20) :
    Mcp23x08Minimal(Mcp23008I2cBus(connection), connection)

/**
 * MCP23S08 8-bit I/O port expander (SPI) — minimal interface.
 *
 * Identical GPIO behaviour to [Mcp23008Minimal]. Every transaction is
 * `[0x40 | (addr << 1) | R/W] [reg] [data…]`; the driver builds the two header
 * bytes itself and talks to a plain [Connection]. IOCON.HAEN is set during init
 * so up to four devices can share one chip select.
 *
 * @param connection SPI connection (mode 0, MSB first, CS active low)
 * @param addr       hardware device address 0–3 (A1:A0 pins)
 */
open class Mcp23S08Minimal(connection: Connection, val addr: Int = 0) :
    Mcp23x08Minimal(Mcp23S08SpiBus(connection, addr), connection)
