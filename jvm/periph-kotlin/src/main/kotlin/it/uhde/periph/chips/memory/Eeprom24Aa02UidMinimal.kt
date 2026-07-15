package it.uhde.periph.chips.memory

import it.uhde.periph.transport.Transport

/**
 * 24AA02UID — 2 Kbit I²C EEPROM with 32-bit unique serial number (minimal driver).
 *
 * Provides access to the chip's factory-programmed 32-bit unique serial
 * number and basic single-byte read/write access to the user EEPROM. The
 * chip has no configuration registers; it is ready for use immediately
 * after power-on.
 *
 * Default I²C address: 0x50 (fixed — A0, A1, A2 pins are not connected).
 *
 * ## Memory layout
 * - 0x00-0x7F — 128 bytes general-purpose user EEPROM (R/W)
 * - 0x80-0xF9 — reserved, read-only
 * - 0xFA      — manufacturer code (always 0x29, Microchip)
 * - 0xFB      — device code (always 0x41)
 * - 0xFC-0xFF — 32-bit unique serial number, MSB first
 *
 * ## Configuration defaults
 * - User EEPROM is read/written as raw bytes (no interpretation)
 * - [writeByte] waits the worst-case 5 ms internal write cycle before
 *   returning (the C++ Transport interface does not propagate ACK/NACK)
 * - All addresses 0x80-0xFF are write-protected; writes are silently
 *   ignored by the chip
 */
open class Eeprom24Aa02UidMinimal(
    protected val transport: Transport
) {
    companion object {
        const val ADDR_UID_BASE  = 0xFC
        const val ADDR_MFR_CODE  = 0xFA
        const val ADDR_DEV_CODE  = 0xFB
        const val WRITE_CYCLE_MS = 5L
    }

    /**
     * Read the chip's factory-programmed 32-bit unique serial number.
     *
     * Reads 4 bytes at 0xFC-0xFF, MSB first.
     *
     * @return 4-byte UID array
     */
    fun readUid(): ByteArray =
        transport.writeRead(byteArrayOf(ADDR_UID_BASE.toByte()), 4)

    /**
     * Read a single byte from user EEPROM at 0x00-0x7F.
     *
     * @param address memory address 0-127
     * @return byte value 0-255
     */
    fun readByte(address: Int): Int {
        val b = transport.writeRead(byteArrayOf(address.toByte()), 1)
        return b[0].toInt() and 0xFF
    }

    /**
     * Write a single byte to user EEPROM at 0x00-0x7F and wait for completion.
     *
     * Sends the byte, then waits the worst-case 5 ms internal write
     * cycle before returning. Writes to 0x80-0xFF are accepted by the
     * device but silently ignored (write-protected region).
     *
     * @param address memory address 0-255
     * @param value   byte value 0-255
     */
    fun writeByte(address: Int, value: Int) {
        transport.write(byteArrayOf(address.toByte(), value.toByte()))
        Thread.sleep(WRITE_CYCLE_MS)
    }
}
