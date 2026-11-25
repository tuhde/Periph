package it.uhde.periph.chips.memory

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * CAT24C512 — 512 Kbit (64 KiB) I²C EEPROM (minimal driver).
 *
 * Writes are self-timed (5 ms max) and completed by ACK polling: after every write the driver
 * polls the device address until the chip answers again, so a returned write is always committed.
 * The chip has no configuration registers; it is ready within 1 ms of power-up.
 *
 * I²C address 0x50-0x57, set by the A2:A0 pins. The memory address is two bytes (16 bits,
 * big-endian), so the connection must be created with `regBytes` = 2. No I²C traffic occurs at
 * construction.
 *
 * ## Memory layout
 * - 0x0000-0xFFFF — 65,536 bytes in 512 pages of 128 bytes
 *
 * ## Configuration defaults
 * - The full 64 KiB is addressable as raw bytes
 * - [writeByte] returns after the write cycle has finished
 * - Writes are ignored while the WP pin is high (hardware only; the driver cannot detect it)
 */
open class CAT24C512Minimal(protected val connection: RegisterConnection) {
    companion object {
        const val SIZE = 0x10000
        const val PAGE = 128
        /** tWR = 5 ms max, plus margin. */
        const val WRITE_TIMEOUT_MS = 10L
    }

    /** Validate a single address; throws [IllegalArgumentException] if outside 0-65535. */
    protected fun checkAddress(address: Int) {
        require(address in 0 until SIZE) { "address out of range 0-65535" }
    }

    /** ACK-poll: an address-only write is acknowledged once the write cycle has ended. */
    protected fun pollReady(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            try {
                connection.write(0, ByteArray(0))
                return true
            } catch (nack: IOException) {
                // not acknowledged while the write cycle is running
            }
            if (System.nanoTime() >= deadline) return false
            try {
                Thread.sleep(0, 500_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    /** Wait for the write cycle that was just started; throws [IOException] if it does not finish. */
    protected fun commit() {
        if (!pollReady(WRITE_TIMEOUT_MS)) throw IOException("CAT24C512 write cycle did not complete")
    }

    /**
     * Read a single byte (random read).
     *
     * @param address memory address 0-65535
     * @return byte value 0-255
     */
    fun readByte(address: Int): Int {
        checkAddress(address)
        return connection.read(address, 1)[0].toInt() and 0xFF
    }

    /**
     * Write a single byte, then ACK-poll until the write cycle completes.
     *
     * @param address memory address 0-65535
     * @param value   byte value 0-255
     */
    fun writeByte(address: Int, value: Int) {
        checkAddress(address)
        connection.write(address, byteArrayOf(value.toByte()))
        commit()
    }
}
