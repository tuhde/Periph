package it.uhde.periph.chips.memory

import it.uhde.periph.connection.RegisterConnection

/**
 * AT24C02N — full driver. Extends [AT24C02NMinimal] with sequential read, page-boundary-aware
 * write, single page write, range fill, the array and page size and explicit ACK polling.
 *
 * Writes never cross a 8-byte page boundary (that would wrap inside the page and corrupt data)
 * and every chunk is ACK-polled. Ranges that would run past 0x00FF are rejected instead of
 * wrapping to 0x0000 as the hardware would do.
 */
open class AT24C02NFull(connection: RegisterConnection) : AT24C02NMinimal(connection) {

    /**
     * Maximum data bytes per bus transaction (default 30; Arduino Wire limits a transfer to 32
     * bytes including the address byte). Must be at least 1.
     */
    var maxChunk: Int = 30
        set(value) {
            require(value >= 1) { "maxChunk must be >= 1" }
            field = value
        }

    private fun checkRange(address: Int, length: Int) {
        require(length >= 0 && address >= 0 && address.toLong() + length <= SIZE) { "range outside 0-255" }
    }

    private fun chunkLen(address: Int, remaining: Int): Int =
        minOf(PAGE - address % PAGE, maxChunk, remaining)

    /** Array size in bytes (256). */
    fun size(): Int = SIZE

    /** Write page size in bytes (8). */
    fun pageSize(): Int = PAGE

    /** ACK-poll until the chip answers its device address again; true if it did within [timeoutMs]. */
    fun waitReady(timeoutMs: Int): Boolean = pollReady(timeoutMs.toLong())

    /**
     * Sequential read, split into transactions of at most [maxChunk] bytes.
     *
     * @param address starting address 0-255
     * @param length  number of bytes
     * @return the bytes read
     */
    fun read(address: Int, length: Int): ByteArray {
        checkRange(address, length)
        val out = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = minOf(maxChunk, length - off)
            connection.read(address + off, n).copyInto(out, off)
            off += n
        }
        return out
    }

    /**
     * Write [data] starting at [address], split at 8-byte page boundaries and [maxChunk]; the
     * write cycle is ACK-polled after every chunk.
     */
    fun write(address: Int, data: ByteArray) {
        checkRange(address, data.size)
        var off = 0
        while (off < data.size) {
            val n = chunkLen(address + off, data.size - off)
            connection.write(address + off, data.copyOfRange(off, off + n))
            commit()
            off += n
        }
    }

    /**
     * Write data that lies inside one 8-byte page (sent in [maxChunk] pieces), ACK-polled.
     *
     * @throws IllegalArgumentException if the data crosses a page boundary or runs outside 0x00-0x00FF
     */
    fun writePage(address: Int, data: ByteArray) {
        checkRange(address, data.size)
        require(data.size <= PAGE && address % PAGE + data.size <= PAGE) { "write crosses a page boundary" }
        write(address, data)
    }

    /** Write [value] over a range, page by page. */
    @JvmOverloads
    fun fill(address: Int, length: Int, value: Int = 0) {
        checkRange(address, length)
        var done = 0
        while (done < length) {
            val n = chunkLen(address + done, length - done)
            connection.write(address + done, ByteArray(n) { value.toByte() })
            commit()
            done += n
        }
    }
}
