package it.uhde.periph.chips.memory

import groovy.transform.CompileStatic
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
 * regBytes = 2.
 *
 * Memory layout: 0x0000-0x7FFF — 32,768 bytes of FRAM.
 *
 * Writes are ignored by the chip while the WP pin is high (hardware only).
 */
@CompileStatic
class MB85RCMinimal {

    protected static final int SIZE = 0x8000

    protected final RegisterConnection connection

    /**
     * Construct the driver. No I²C traffic occurs at construction.
     *
     * @param connection I²C connection with regBytes = 2, bound to 0x50-0x57
     */
    MB85RCMinimal(RegisterConnection connection) {
        this.connection = connection
    }

    /**
     * Validate a single address.
     *
     * @param address memory address
     * @throws IllegalArgumentException if outside 0-32767
     */
    protected static void checkAddress(int address) {
        if (address < 0 || address >= SIZE) {
            throw new IllegalArgumentException('address out of range 0-32767')
        }
    }

    /**
     * Read a single byte (random read).
     *
     * @param address memory address 0-32767
     * @return byte value 0-255
     */
    int readByte(int address) {
        checkAddress(address)
        connection.read(address, 1)[0] & 0xFF
    }

    /**
     * Write a single byte; returns when the transfer completes (no polling needed).
     *
     * @param address memory address 0-32767
     * @param value   byte value 0-255
     */
    void writeByte(int address, int value) {
        checkAddress(address)
        connection.write(address, [(byte) value] as byte[])
    }
}
