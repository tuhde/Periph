package it.uhde.periph.chips.memory

import it.uhde.periph.connection.RegisterConnection

/**
 * MB85RC256V — 256 Kbit (32 KiB) I²C FRAM (minimal driver).
 *
 * Ferroelectric RAM: non-volatile like EEPROM, but every write completes at
 * bus speed — no write cycle, no page buffer, no ACK polling. The chip has no
 * configuration registers; it is ready for use immediately after power-on.
 *
 * I²C address 0x50-0x57, set by the A2:A0 pins. The memory address is two
 * bytes (15 bits, big-endian), so the connection must be created with
 * `regBytes` = 2.
 *
 * Memory layout: 0x0000-0x7FFF — 32,768 bytes of FRAM.
 *
 * Writes are ignored by the chip while the WP pin is high (hardware only).
 *
 * @param connection I²C connection with `regBytes` = 2, bound to 0x50-0x57
 */
open class MB85RCMinimal(
    protected val connection: RegisterConnection
) {
    companion object {
        const val SIZE = 0x8000

        /** Validate a single address; throws [IllegalArgumentException] if outside 0-32767. */
        @JvmStatic
        fun checkAddress(address: Int) {
            require(address in 0 until SIZE) { "address out of range 0-32767" }
        }
    }

    /**
     * Read a single byte (random read).
     *
     * @param address memory address 0-32767
     * @return byte value 0-255
     */
    fun readByte(address: Int): Int {
        checkAddress(address)
        return connection.read(address, 1)[0].toInt() and 0xFF
    }

    /**
     * Write a single byte; returns when the transfer completes (no polling needed).
     *
     * @param address memory address 0-32767
     * @param value   byte value 0-255
     */
    fun writeByte(address: Int, value: Int) {
        checkAddress(address)
        connection.write(address, byteArrayOf(value.toByte()))
    }
}
