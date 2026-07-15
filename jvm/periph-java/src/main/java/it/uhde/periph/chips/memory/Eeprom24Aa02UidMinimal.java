package it.uhde.periph.chips.memory;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

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
 *       write cycle before returning (the C++ Transport interface does
 *       not propagate ACK/NACK, so the bus is not polled)</li>
 *   <li>All addresses 0x80-0xFF are write-protected; writes are silently
 *       ignored by the chip</li>
 * </ul>
 */
public class Eeprom24Aa02UidMinimal {

    protected static final int  ADDR_UID_BASE   = 0xFC;
    protected static final int  ADDR_MFR_CODE   = 0xFA;
    protected static final int  ADDR_DEV_CODE   = 0xFB;
    protected static final int  WRITE_CYCLE_MS  = 5;

    protected final Transport transport;

    /**
     * Construct the driver.
     *
     * <p>The chip has no configuration registers; no I²C traffic occurs
     * at construction.
     *
     * @param transport I²C transport bound to the device address (0x50)
     */
    public Eeprom24Aa02UidMinimal(Transport transport) {
        this.transport = transport;
    }

    /**
     * Read the chip's factory-programmed 32-bit unique serial number.
     *
     * <p>Reads 4 bytes at 0xFC-0xFF, MSB first.
     *
     * @return 4-byte UID array
     * @throws IOException on I²C error
     */
    public byte[] readUid() throws IOException {
        return transport.writeRead(new byte[]{(byte) ADDR_UID_BASE}, 4);
    }

    /**
     * Read a single byte from user EEPROM at 0x00-0x7F.
     *
     * @param address memory address 0-127
     * @return byte value 0-255
     * @throws IOException on I²C error
     */
    public int readByte(int address) throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) address}, 1);
        return b[0] & 0xFF;
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
     * @throws IOException on I²C error
     */
    public void writeByte(int address, int value) throws IOException {
        transport.write(new byte[]{(byte) address, (byte) value});
        sleep(WRITE_CYCLE_MS);
    }

    protected static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
