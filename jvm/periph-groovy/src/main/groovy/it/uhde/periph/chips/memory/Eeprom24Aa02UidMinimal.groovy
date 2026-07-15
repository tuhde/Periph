package it.uhde.periph.chips.memory

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * 24AA02UID — 2 Kbit I²C EEPROM with 32-bit unique serial number (minimal driver).
 *
 * <p>Provides access to the chip's factory-programmed 32-bit unique serial
 * number and basic single-byte read/write access to the user EEPROM. The
 * chip has no configuration registers; it is ready for use immediately
 * after power-on.
 *
 * <p>Default I²C address: 0x50 (fixed — A0, A1, A2 pins are not connected).
 *
 * <h2>Memory layout</h2>
 * <ul>
 *   <li>0x00-0x7F — 128 bytes general-purpose user EEPROM (R/W)</li>
 *   <li>0x80-0xF9 — reserved, read-only</li>
 *   <li>0xFA      — manufacturer code (always 0x29, Microchip)</li>
 *   <li>0xFB      — device code (always 0x41)</li>
 *   <li>0xFC-0xFF — 32-bit unique serial number, MSB first</li>
 * </ul>
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>User EEPROM is read/written as raw bytes (no interpretation)</li>
 *   <li>{@link #writeByte(int, int)} waits the worst-case 5 ms internal
 *       write cycle before returning</li>
 *   <li>All addresses 0x80-0xFF are write-protected; writes are silently
 *       ignored by the chip</li>
 * </ul>
 */
@CompileStatic
class Eeprom24Aa02UidMinimal {

    protected static final int  ADDR_UID_BASE   = 0xFC
    protected static final int  ADDR_MFR_CODE   = 0xFA
    protected static final int  ADDR_DEV_CODE   = 0xFB
    protected static final long WRITE_CYCLE_MS  = 5

    protected final Transport transport

    /**
     * Construct the driver.
     *
     * @param transport I²C transport bound to the device address (0x50)
     */
    Eeprom24Aa02UidMinimal(Transport transport) {
        this.transport = transport
    }

    /**
     * Read the chip's factory-programmed 32-bit unique serial number.
     *
     * <p>Reads 4 bytes at 0xFC-0xFF, MSB first.
     *
     * @return 4-byte UID array
     */
    byte[] readUid() {
        transport.writeRead([(byte) ADDR_UID_BASE] as byte[], 4)
    }

    /**
     * Read a single byte from user EEPROM at 0x00-0x7F.
     *
     * @param address memory address 0-127
     * @return byte value 0-255
     */
    int readByte(int address) {
        byte[] b = transport.writeRead([(byte) address] as byte[], 1)
        b[0] & 0xFF
    }

    /**
     * Write a single byte to user EEPROM at 0x00-0x7F and wait for completion.
     *
     * <p>Sends the byte, then waits the worst-case 5 ms internal write
     * cycle before returning. Writes to 0x80-0xFF are accepted by the
     * device but silently ignored (write-protected region).
     *
     * @param address memory address 0-255
     * @param value   byte value 0-255
     */
    void writeByte(int address, int value) {
        transport.write([(byte) address, (byte) value] as byte[])
        Thread.sleep(WRITE_CYCLE_MS)
    }
}
