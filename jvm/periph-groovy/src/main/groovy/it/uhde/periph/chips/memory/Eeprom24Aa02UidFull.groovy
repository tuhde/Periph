package it.uhde.periph.chips.memory

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * 24AA02UID — full driver. Extends {@link Eeprom24Aa02UidMinimal} with
 * multi-byte read/write support: sequential read, raw page write
 * (8-byte page), arbitrary-length write that automatically crosses
 * page boundaries, and accessors for the manufacturer and device codes
 * in the upper (read-only) block.
 */
@CompileStatic
class Eeprom24Aa02UidFull extends Eeprom24Aa02UidMinimal {

    private static final int PAGE_SIZE = 8

    /**
     * Construct the full driver.
     *
     * @param transport I²C transport bound to the device address (0x50)
     */
    Eeprom24Aa02UidFull(Transport transport) {
        super(transport)
    }

    /**
     * Sequential read of {@code length} bytes starting at {@code address}.
     *
     * <p>The internal address pointer auto-increments; reads may cross
     * any boundary and wrap at the end of the 256-byte address space.
     *
     * @param address starting address 0-255
     * @param length  number of bytes to read
     * @return bytes read from the device
     */
    byte[] read(int address, int length) {
        transport.writeRead([(byte) address] as byte[], length)
    }

    /**
     * Write up to 8 bytes within a single 8-byte page.
     *
     * <p>The caller is responsible for ensuring all bytes lie within the
     * same page. Bytes that would overflow the page boundary wrap to the
     * start of the same page (FIFO overwrite) — use {@link #write(int, byte[])}
     * to handle boundaries automatically.
     *
     * @param address start address within an 8-byte page (0, 8, 16, …)
     * @param data    bytes to write (1 to 8 bytes)
     */
    void writePage(int address, byte[] data) {
        if (data.length == 0) return
        byte[] buf = new byte[1 + data.length]
        buf[0] = (byte) address
        System.arraycopy(data, 0, buf, 1, data.length)
        transport.write(buf)
        Thread.sleep(WRITE_CYCLE_MS)
    }

    /**
     * Write an arbitrary-length buffer, splitting at 8-byte page boundaries.
     *
     * <p>Automatically splits the write into page-aligned chunks and
     * waits for the write cycle of each chunk before continuing.
     *
     * @param address starting address 0-255
     * @param data    bytes to write
     */
    void write(int address, byte[] data) {
        int offset = 0
        int remaining = data.length
        int current = address
        while (remaining > 0) {
            int pageOffset = current & (PAGE_SIZE - 1)
            int chunk = PAGE_SIZE - pageOffset
            if (chunk > remaining) chunk = remaining
            byte[] slice = new byte[chunk]
            System.arraycopy(data, offset, slice, 0, chunk)
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
    int readManufacturerCode() {
        readByte(ADDR_MFR_CODE)
    }

    /**
     * Read the device code at 0xFB.
     *
     * @return device code; expect 0x41 (I²C 2-Kbit EEPROM)
     */
    int readDeviceCode() {
        readByte(ADDR_DEV_CODE)
    }
}
