package it.uhde.periph.chips.rfid

import it.uhde.periph.connection.RegisterConnection
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * ST25DV04K / ST25DV16K / ST25DV64K — dynamic NFC/RFID tag (minimal driver): user memory as a
 * byte store.
 *
 * The chip answers on two I²C addresses, both with 16-bit register addresses: 0x53 (user memory,
 * dynamic registers, fast-transfer-mode mailbox) and 0x57 (system configuration). The driver takes
 * two register connections on the same bus, both created with `regBytes` = 2.
 *
 * Construction reads `MEM_SIZE`, `BLK_SIZE` and `IC_REF` and derives the memory size. No
 * configuration is written; the chip keeps its delivery state (area 1 = the whole memory, no
 * protection, `GPO_EN` = 1, field-change interrupt only).
 *
 * Reads and writes are split into transfers of at most 16 bytes aligned to 16-byte boundaries;
 * every write is completed by ACK polling (5 ms per 4-byte EEPROM page). Reads are retried up to
 * 3 times while an RF reader holds the EEPROM.
 *
 * @param user connection at 0x53 (`regBytes` = 2)
 * @param system connection at 0x57 (`regBytes` = 2)
 * @param gpoActiveHigh true for -JF variants (CMOS GPO, rising edge), false for -IE (falling edge)
 */
open class St25dvMinimal @JvmOverloads constructor(
    protected val user: RegisterConnection,
    protected val system: RegisterConnection,
    protected val gpoActiveHigh: Boolean = false
) {
    companion object {
        const val SYS_MEM_SIZE = 0x0014
        const val CHUNK = 16
        const val WRITE_TIMEOUT_MS = 50L
        const val READ_RETRIES = 3
    }

    /** User memory size in bytes. */
    protected val size: Int

    /** IC_REF value. */
    protected val icRef: Int

    init {
        val id = readReg(system, SYS_MEM_SIZE, 4)
        val mem = (id[0].toInt() and 0xFF) or ((id[1].toInt() and 0xFF) shl 8)
        size = (mem + 1) * ((id[2].toInt() and 0xFF) + 1)
        icRef = id[3].toInt() and 0xFF
    }

    /** Register read with up to [READ_RETRIES] attempts, 5 ms apart. */
    protected fun readReg(c: RegisterConnection, reg: Int, length: Int): ByteArray {
        var attempt = 1
        while (true) {
            try {
                return c.read(reg, length)
            } catch (e: IOException) {
                if (attempt >= READ_RETRIES) throw e
                attempt++
                sleepMs(5)
            }
        }
    }

    /** ACK-poll: an address-only write is acknowledged once the write cycle has ended. */
    protected fun ackPoll(c: RegisterConnection, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            try {
                c.write(0, ByteArray(0))
                return true
            } catch (nack: IOException) {
                // not acknowledged while the write cycle is running
            }
            if (System.nanoTime() >= deadline) return false
            sleepMs(1)
        }
    }

    /** ACK-poll and throw [IOException] if the write cycle does not complete. */
    protected fun commit(c: RegisterConnection) {
        if (!ackPoll(c, WRITE_TIMEOUT_MS)) throw IOException("ST25DV write cycle did not complete")
    }

    /** Sleep, restoring the interrupt flag if interrupted. */
    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Validate a user-memory range; throws [IllegalArgumentException] outside the memory. */
    protected fun checkRange(address: Int, length: Int) {
        require(address >= 0 && length >= 0 && address + length <= size) {
            "range outside the user memory 0-${size - 1}"
        }
    }

    /**
     * Read user memory.
     *
     * @param address start byte address
     * @param length number of bytes
     * @return `length` bytes
     */
    fun read(address: Int, length: Int): ByteArray {
        checkRange(address, length)
        val out = ByteArrayOutputStream(length)
        var a = address
        var left = length
        while (left > 0) {
            val n = minOf(CHUNK - a % CHUNK, left)
            out.writeBytes(readReg(user, a, n))
            a += n
            left -= n
        }
        return out.toByteArray()
    }

    /**
     * Write user memory in 16-byte-aligned pieces, ACK-polling after each.
     *
     * @param address start byte address
     * @param data bytes to write
     */
    fun write(address: Int, data: ByteArray) {
        checkRange(address, data.size)
        var off = 0
        while (off < data.size) {
            val a = address + off
            val n = minOf(CHUNK - a % CHUNK, data.size - off)
            user.write(a, data.copyOfRange(off, off + n))
            commit(user)
            off += n
        }
    }
}
