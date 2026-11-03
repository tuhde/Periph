package it.uhde.periph.chips.memory

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * 23LC1024 / 23A1024 — 1-Mbit SPI serial SRAM (minimal driver).
 *
 * Byte read/write access to the 131,072-byte array over plain SPI (Mode 0). The
 * chip is volatile but has unlimited cycles and zero write time: a write is
 * complete when CS is released, with no polling.
 *
 * Default configuration: Sequential mode (MODE register = 0x40), written and read
 * back at construction; a mismatch (floating/missing MISO, wrong SPI mode, chip
 * stuck in SDI/SQI mode) throws an IOException. The full 128 KiB is addressable;
 * SDI/SQI bus modes are never used.
 *
 * Every transaction is one instruction byte, a 24-bit big-endian address and
 * data, with CS held low for the whole frame, so the driver takes the bare
 * Connection and uses write/writeRead directly (the instruction is not a
 * register address, so RegisterConnection's read-bit logic does not apply).
 */
open class Sram23x1024Minimal @Throws(IOException::class) constructor(
    protected val connection: Connection
) {
    companion object {
        /** Array size in bytes. */
        const val SIZE = 131072

        protected const val CMD_READ = 0x03
        protected const val CMD_WRITE = 0x02
        protected const val CMD_RDMR = 0x05
        protected const val CMD_WRMR = 0x01
        protected const val CMD_RSTIO = 0xFF
        protected const val MODE_MASK = 0xC0
    }

    init {
        writeMode(0x40)
        val mode = readMode()
        if (mode != 0x40) {
            throw IOException(String.format("23X1024 MODE register 0x%02X, expected 0x40 (check wiring and SPI Mode 0)", mode))
        }
    }

    @Throws(IOException::class)
    protected fun readMode(): Int = connection.writeRead(byteArrayOf(CMD_RDMR.toByte()), 1)[0].toInt() and 0xFF

    @Throws(IOException::class)
    protected fun writeMode(mode: Int) {
        connection.write(byteArrayOf(CMD_WRMR.toByte(), mode.toByte()))
    }

    private fun header(command: Int, address: Int): ByteArray =
        byteArrayOf(command.toByte(), (address shr 16).toByte(), (address shr 8).toByte(), address.toByte())

    @Throws(IOException::class)
    protected fun readRaw(address: Int, length: Int): ByteArray = connection.writeRead(header(CMD_READ, address), length)

    @Throws(IOException::class)
    protected fun writeRaw(address: Int, data: ByteArray, offset: Int, length: Int) {
        connection.write(header(CMD_WRITE, address) + data.copyOfRange(offset, offset + length))
    }

    /**
     * Read one byte (READ, 0x03).
     *
     * @param address 0-131071
     * @return byte value 0-255
     * @throws IllegalArgumentException if the address is out of range
     */
    @Throws(IOException::class)
    fun readByte(address: Int): Int {
        require(address in 0 until SIZE) { "address out of range" }
        return readRaw(address, 1)[0].toInt() and 0xFF
    }

    /**
     * Write one byte (WRITE, 0x02); complete when CS is released.
     *
     * @param address 0-131071
     * @param value   byte value 0-255
     * @throws IllegalArgumentException if the address is out of range
     */
    @Throws(IOException::class)
    fun writeByte(address: Int, value: Int) {
        require(address in 0 until SIZE) { "address out of range" }
        writeRaw(address, byteArrayOf(value.toByte()), 0, 1)
    }
}
