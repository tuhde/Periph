package it.uhde.periph.chips.memory

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * 23X1024 — full driver. Extends the minimal driver with block access,
 * operating modes and I/O reset.
 *
 * read/write/fill have linear-address semantics in every mode: transfers are
 * split into transactions of at most maxChunk() data bytes and, in Page/Byte
 * mode, at the 32-byte page / single-byte boundaries the hardware would
 * otherwise wrap or truncate at. Ranges that would leave the array throw
 * IllegalArgumentException instead of silently wrapping.
 */
open class Sram23x1024Full @Throws(IOException::class) constructor(
    connection: Connection
) : Sram23x1024Minimal(connection) {

    companion object {
        /** Operating mode: each READ/WRITE transfers exactly one data byte. */
        const val MODE_BYTE = 0x00
        /** Operating mode: auto-increment wraps within the 32-byte page. */
        const val MODE_PAGE = 0x80
        /** Operating mode: auto-increment across the whole array (power-on default). */
        const val MODE_SEQUENTIAL = 0x40

        private const val PAGE_SIZE = 32
        private const val MAX_CHUNK_LIMIT = 256
    }

    private var mode = MODE_SEQUENTIAL
    private var maxChunkBytes = MAX_CHUNK_LIMIT

    private fun chunkLen(address: Int, remaining: Int): Int {
        var n = minOf(remaining, maxChunkBytes)
        if (mode == MODE_BYTE) return 1
        if (mode == MODE_PAGE) n = minOf(n, PAGE_SIZE - (address % PAGE_SIZE))
        return n
    }

    private fun checkRange(address: Int, length: Int) {
        require(address >= 0 && length >= 0 && address.toLong() + length <= SIZE) { "range leaves the 128 KiB array" }
    }

    /**
     * Block read.
     *
     * @param address start address; address + length must be <= 131072
     * @param length  number of bytes
     * @return the bytes read
     */
    @Throws(IOException::class)
    fun read(address: Int, length: Int): ByteArray {
        checkRange(address, length)
        val out = ByteArray(length)
        var addr = address
        var pos = 0
        while (pos < length) {
            val n = chunkLen(addr, length - pos)
            readRaw(addr, n).copyInto(out, pos, 0, n)
            addr += n
            pos += n
        }
        return out
    }

    /**
     * Block write (same range rule as [read]).
     *
     * @param address start address
     * @param data    bytes to write
     */
    @Throws(IOException::class)
    fun write(address: Int, data: ByteArray) {
        checkRange(address, data.size)
        var addr = address
        var pos = 0
        while (pos < data.size) {
            val n = chunkLen(addr, data.size - pos)
            writeRaw(addr, data, pos, n)
            addr += n
            pos += n
        }
    }

    /**
     * Write [value] over [length] bytes starting at [address] (chunked).
     *
     * @param address start address
     * @param length  number of bytes
     * @param value   byte value 0-255
     */
    @Throws(IOException::class)
    fun fill(address: Int, length: Int, value: Int) {
        checkRange(address, length)
        val block = ByteArray(PAGE_SIZE) { value.toByte() }
        var addr = address
        var remaining = length
        while (remaining > 0) {
            val n = minOf(chunkLen(addr, remaining), block.size)
            writeRaw(addr, block, 0, n)
            addr += n
            remaining -= n
        }
    }

    /** Array size in bytes (131072). */
    fun size(): Int = SIZE

    /**
     * Select the operating mode (WRMR) and verify it with RDMR.
     *
     * @param mode [MODE_BYTE], [MODE_PAGE] or [MODE_SEQUENTIAL]
     * @throws IllegalArgumentException for any other value
     */
    @Throws(IOException::class)
    fun setMode(mode: Int) {
        require(mode == MODE_BYTE || mode == MODE_PAGE || mode == MODE_SEQUENTIAL) {
            "mode must be MODE_BYTE, MODE_PAGE or MODE_SEQUENTIAL"
        }
        writeMode(mode)
        val got = readMode()
        if (got != mode) throw IOException(String.format("23X1024 MODE read-back 0x%02X, expected 0x%02X", got, mode))
        this.mode = mode
    }

    /** Read the operating mode (RDMR bits 7:6) as one of the MODE_* constants (0xC0 if reserved). */
    @Throws(IOException::class)
    fun getMode(): Int = readMode() and MODE_MASK

    /**
     * Send RSTIO (0xFF) to return the chip to SPI mode after a stray EDIO/EQIO,
     * then resynchronise the cached mode with the chip.
     */
    @Throws(IOException::class)
    fun resetIo() {
        connection.write(byteArrayOf(CMD_RSTIO.toByte()))
        mode = getMode()
    }

    /** Maximum data bytes per bus transaction (1-256, default 256). */
    fun maxChunk(): Int = maxChunkBytes

    /**
     * Set the maximum data bytes per bus transaction.
     *
     * @param n 1-256
     * @throws IllegalArgumentException if out of range
     */
    fun setMaxChunk(n: Int) {
        require(n in 1..MAX_CHUNK_LIMIT) { "maxChunk must be 1-256" }
        maxChunkBytes = n
    }
}
