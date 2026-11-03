package it.uhde.periph.chips.memory

import it.uhde.periph.connection.RegisterConnection

/**
 * MB85RC256V — full driver. Extends [MB85RCMinimal] with sequential
 * read/write split into bus-sized chunks, range fill, the array size and the
 * 3-byte Device ID read through the reserved slave address 0x7C.
 *
 * Ranges that would run past 0x7FFF are rejected instead of silently
 * wrapping to 0x0000 as the hardware would do.
 */
class MB85RCFull(
    connection: RegisterConnection
) : MB85RCMinimal(connection) {

    companion object {
        private const val DEVICE_ID_BASE = 0xA0
    }

    /**
     * Maximum data bytes per bus transaction (default 30; Arduino Wire limits a
     * transfer to 32 bytes including the 2 address bytes). Must be at least 1.
     */
    var maxChunk: Int = 30
        set(value) {
            require(value >= 1) { "maxChunk must be >= 1" }
            field = value
        }

    private fun checkRange(address: Int, length: Int) {
        require(length >= 0 && address >= 0 && address.toLong() + length <= SIZE) { "range outside 0-32767" }
    }

    /** Array size in bytes (32768). */
    fun size(): Int = SIZE

    /**
     * Sequential read, split into chunks of [maxChunk] bytes.
     *
     * @param address starting address 0-32767
     * @param length  number of bytes to read
     * @return bytes read from the device
     */
    fun read(address: Int, length: Int): ByteArray {
        checkRange(address, length)
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = minOf(maxChunk, length - done)
            connection.read(address + done, n).copyInto(out, done, 0, n)
            done += n
        }
        return out
    }

    /**
     * Sequential write, split into chunks of [maxChunk] bytes; each chunk is a
     * fresh transaction. No delay between chunks.
     *
     * @param address starting address 0-32767
     * @param data    bytes to write
     */
    fun write(address: Int, data: ByteArray) {
        checkRange(address, data.size)
        var done = 0
        while (done < data.size) {
            val n = minOf(maxChunk, data.size - done)
            connection.write(address + done, data.copyOfRange(done, done + n))
            done += n
        }
    }

    /**
     * Write [value] over a range, in chunks.
     *
     * @param address starting address 0-32767
     * @param length  number of bytes to fill
     * @param value   byte value 0-255 (default 0)
     */
    fun fill(address: Int, length: Int, value: Int = 0) {
        checkRange(address, length)
        var done = 0
        while (done < length) {
            val n = minOf(maxChunk, length - done)
            connection.write(address + done, ByteArray(n) { value.toByte() })
            done += n
        }
    }

    /**
     * Read the Device ID via the reserved slave address 0x7C.
     *
     * The device address word is `0xA0 + (A2A1A0 × 2)`.
     *
     * @param idConnection connection (`regBytes` = 1) bound to I²C address 0x7C
     * @param address      7-bit address of the FRAM (0x50-0x57), default 0x50
     * @return intArrayOf(manufacturer, density, product); 0x00A, 0x5, 0x10 for the MB85RC256V
     */
    fun readDeviceId(idConnection: RegisterConnection, address: Int = 0x50): IntArray {
        val word = DEVICE_ID_BASE + ((address and 0x07) shl 1)
        val raw = idConnection.read(word, 3)
        val b0 = raw[0].toInt() and 0xFF
        val b1 = raw[1].toInt() and 0xFF
        val b2 = raw[2].toInt() and 0xFF
        return intArrayOf((b0 shl 4) or (b1 shr 4), b1 and 0x0F, b2)
    }
}
