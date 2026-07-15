package it.uhde.periph.chips.memory

import it.uhde.periph.transport.Transport

/**
 * 24AA02UID — full driver. Extends [Eeprom24Aa02UidMinimal] with
 * multi-byte read/write support: sequential read, raw page write
 * (8-byte page), arbitrary-length write that automatically crosses
 * page boundaries, and accessors for the manufacturer and device codes
 * in the upper (read-only) block.
 */
class Eeprom24Aa02UidFull(
    transport: Transport
) : Eeprom24Aa02UidMinimal(transport) {

    companion object {
        const val PAGE_SIZE = 8
    }

    /**
     * Sequential read of [length] bytes starting at [address].
     *
     * The internal address pointer auto-increments; reads may cross any
     * boundary and wrap at the end of the 256-byte address space.
     *
     * @param address starting address 0-255
     * @param length  number of bytes to read
     * @return bytes read from the device
     */
    fun read(address: Int, length: Int): ByteArray =
        transport.writeRead(byteArrayOf(address.toByte()), length)

    /**
     * Write up to 8 bytes within a single 8-byte page.
     *
     * The caller is responsible for ensuring all bytes lie within the
     * same page. Bytes that would overflow the page boundary wrap to the
     * start of the same page (FIFO overwrite) — use [write] to handle
     * boundaries automatically.
     *
     * @param address start address within an 8-byte page (0, 8, 16, …)
     * @param data    bytes to write (1 to 8 bytes)
     */
    fun writePage(address: Int, data: ByteArray) {
        if (data.isEmpty()) return
        val buf = ByteArray(1 + data.size)
        buf[0] = address.toByte()
        System.arraycopy(data, 0, buf, 1, data.size)
        transport.write(buf)
        Thread.sleep(WRITE_CYCLE_MS)
    }

    /**
     * Write an arbitrary-length buffer, splitting at 8-byte page boundaries.
     *
     * Automatically splits the write into page-aligned chunks and waits
     * for the write cycle of each chunk before continuing.
     *
     * @param address starting address 0-255
     * @param data    bytes to write
     */
    fun write(address: Int, data: ByteArray) {
        var offset = 0
        var remaining = data.size
        var current = address
        while (remaining > 0) {
            val pageOffset = current and (PAGE_SIZE - 1)
            var chunk = PAGE_SIZE - pageOffset
            if (chunk > remaining) chunk = remaining
            val slice = data.copyOfRange(offset, offset + chunk)
            writePage(current, slice)
            offset += chunk
            current += chunk
            remaining -= chunk
        }
    }

    /**
     * Read the manufacturer code at 0xFA.
     *
     * @return manufacturer code; expect 0x29 (Microchip)
     */
    fun readManufacturerCode(): Int = readByte(ADDR_MFR_CODE)

    /**
     * Read the device code at 0xFB.
     *
     * @return device code; expect 0x41 (I²C 2-Kbit EEPROM)
     */
    fun readDeviceCode(): Int = readByte(ADDR_DEV_CODE)
}
