package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection
import java.util.Locale

/**
 * MCP4XXX family — MCP453X / 454X / 455X / 456X / 463X / 464X / 465X / 466X I²C digital
 * potentiometers (Microchip), minimal driver.
 *
 * One driver for all sixteen parts. A [PartInfo] descriptor (looked up by part name) carries the
 * channel count, resolution (7-bit: max code 128, 8-bit: 256), pot / rheostat pinout, memory type
 * and I²C address range; everything else is one code path. A transaction starts with a command
 * byte `AD3 AD2 AD1 AD0 C1 C0 D9 D8` that plays the role of the register address of the
 * [RegisterConnection]; a read returns two bytes (`0000 000D8`, `D7..D0`).
 *
 * ## Defaults
 * Part MCP4532, RAB = 10 kΩ, RW = 75 Ω; the wipers keep their power-up value until written; the
 * constructor performs no I²C traffic ([init] does the bus check). An invalid channel or unknown
 * part throws [IllegalArgumentException].
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param part part name `"MCP4531"` … `"MCP4662"` (case-insensitive)
 * @param rAb end-to-end resistance RAB in ohms (5000, 10000, 50000 or 100000)
 * @param rWiper wiper resistance RW in ohms (typically 75)
 */
open class MCP4XXXMinimal(
    protected val connection: RegisterConnection,
    part: String = "MCP4532",
    protected var rAb: Double = 10000.0,
    protected var rWiper: Double = 75.0
) {
    /** One row of the family descriptor table. */
    class PartInfo(
        /** Part name, e.g. `"MCP4532"`. */
        val name: String,
        /** Number of resistor networks (1 or 2). */
        val channels: Int,
        /** Maximum wiper code: 128 (7-bit) or 256 (8-bit). */
        val maxCode: Int,
        /** True for the X1 parts (terminal A on a pin). */
        val potentiometer: Boolean,
        /** True for the 454X / 456X / 464X / 466X parts (EEPROM). */
        val nonvolatile: Boolean,
        /** True for the MCP4641 / MCP4661 (hardware WP pin). */
        val wpPin: Boolean,
        /** Lowest 7-bit I²C address (all address pins low). */
        val addrMin: Int,
        /** Highest 7-bit I²C address. */
        val addrMax: Int
    )

    companion object {
        private val PARTS = listOf(
            PartInfo("MCP4531", 1, 128, true, false, false, 0x2E, 0x2F),
            PartInfo("MCP4532", 1, 128, false, false, false, 0x58, 0x5B),
            PartInfo("MCP4551", 1, 256, true, false, false, 0x2E, 0x2F),
            PartInfo("MCP4552", 1, 256, false, false, false, 0x58, 0x5B),
            PartInfo("MCP4631", 2, 128, true, false, false, 0x28, 0x2F),
            PartInfo("MCP4632", 2, 128, false, false, false, 0x58, 0x5B),
            PartInfo("MCP4651", 2, 256, true, false, false, 0x28, 0x2F),
            PartInfo("MCP4652", 2, 256, false, false, false, 0x58, 0x5B),
            PartInfo("MCP4541", 1, 128, true, true, false, 0x2E, 0x2F),
            PartInfo("MCP4542", 1, 128, false, true, false, 0x58, 0x5B),
            PartInfo("MCP4561", 1, 256, true, true, false, 0x2E, 0x2F),
            PartInfo("MCP4562", 1, 256, false, true, false, 0x58, 0x5B),
            PartInfo("MCP4641", 2, 128, true, true, true, 0x28, 0x2F),
            PartInfo("MCP4642", 2, 128, false, true, false, 0x58, 0x5B),
            PartInfo("MCP4661", 2, 256, true, true, true, 0x28, 0x2F),
            PartInfo("MCP4662", 2, 256, false, true, false, 0x58, 0x5B)
        )

        /** Memory addresses (command byte bits 7:4). */
        const val ADDR_WIPER0 = 0x00
        const val ADDR_NVWIPER0 = 0x02
        const val ADDR_TCON = 0x04
        const val ADDR_STATUS = 0x05
        const val ADDR_DATA0 = 0x06
        const val ADDR_WP = 0x0F

        /** Operations (command byte bits 3:2). */
        const val OP_WRITE = 0x00
        const val OP_INC = 0x04
        const val OP_DEC = 0x08
        const val OP_READ = 0x0C

        /** TCON power-up value (all terminals connected, General Call enabled). */
        const val TCON_DEFAULT = 0x1FF

        /** Per-channel TCON bit positions: HW, A, W, B. */
        val TCON_BITS = arrayOf(intArrayOf(3, 2, 1, 0), intArrayOf(7, 6, 5, 4))

        /**
         * Look a part up by name.
         *
         * @param part part name, e.g. `"mcp4532"` (case-insensitive)
         * @return the descriptor
         * @throws IllegalArgumentException if the part is unknown
         */
        @JvmStatic
        fun lookup(part: String): PartInfo {
            val key = part.uppercase(Locale.ROOT)
            return PARTS.firstOrNull { it.name == key } ?: throw IllegalArgumentException("MCP4XXX: unknown part $part")
        }

        /**
         * Default 7-bit I²C address (all address pins low) of a part: `0x2E` (45X1), `0x58`
         * (45X2 / 46X2) or `0x28` (46X1).
         *
         * @param part part name
         */
        @JvmStatic
        fun defaultAddress(part: String): Int = lookup(part).addrMin
    }

    /** The selected part's descriptor. */
    protected val desc: PartInfo = lookup(part)

    /** Bus check: reads the wiper register(s), TCON (and STATUS on non-volatile parts); performs no writes. */
    fun init() {
        for (c in 0 until desc.channels) readWiper(c)
        read9(ADDR_TCON)
        if (desc.nonvolatile) read9(ADDR_STATUS)
    }

    /** Validate a channel index against the selected part. */
    protected fun checkChannel(channel: Int) {
        require(channel in 0 until desc.channels) { "MCP4XXX: channel out of range for ${desc.name}" }
    }

    /** Read a 9-bit register: command byte with C = 11, two data bytes. */
    protected fun read9(address: Int): Int {
        val d = connection.read((address shl 4) or OP_READ, 2)
        return ((d[0].toInt() and 0x01) shl 8) or (d[1].toInt() and 0xFF)
    }

    /** Write a 9-bit register: D8 travels in the command byte, D7..D0 in the data byte. */
    protected fun write9(address: Int, value: Int) {
        connection.write((address shl 4) or OP_WRITE or ((value shr 8) and 0x01), byteArrayOf(value.toByte()))
    }

    /** Read a volatile wiper register, clamped to the part's max code. */
    protected fun readWiper(channel: Int): Int = minOf(read9(ADDR_WIPER0 + channel), desc.maxCode)

    /** Clamp a code to 0..max code. */
    protected fun clamp(code: Long): Int = code.coerceIn(0L, desc.maxCode.toLong()).toInt()

    /**
     * Set the volatile wiper of a network; the code is clamped to 0..max code. A network locked by
     * WiperLock ignores the write.
     *
     * @param code wiper position (0 = W at B, max code 128 / 256 = W at A)
     * @param channel network index, 0 or 1 (dual parts only)
     */
    fun setWiper(code: Int, channel: Int = 0) {
        checkChannel(channel)
        write9(ADDR_WIPER0 + channel, clamp(code.toLong()))
    }

    /**
     * Read the volatile wiper of a network.
     *
     * @param channel network index
     * @return wiper position 0..max code (values above full scale are clamped)
     */
    fun getWiper(channel: Int = 0): Int {
        checkChannel(channel)
        return readWiper(channel)
    }

    /**
     * Set the W–B resistance to the nearest reachable value.
     *
     * @param ohms target wiper-to-B resistance in ohms (clamped to the reachable range)
     * @param channel network index
     */
    fun setResistance(ohms: Double, channel: Int = 0) {
        checkChannel(channel)
        write9(ADDR_WIPER0 + channel, clamp(Math.round((ohms - rWiper) / rAb * desc.maxCode)))
    }

    /**
     * Read the W–B resistance for the current wiper position.
     *
     * @param channel network index
     * @return resistance in ohms, N / maxCode × RAB + RW
     */
    fun getResistance(channel: Int = 0): Double = getWiper(channel).toDouble() / desc.maxCode * rAb + rWiper
}
