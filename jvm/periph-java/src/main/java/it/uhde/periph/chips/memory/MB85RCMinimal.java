package it.uhde.periph.chips.memory;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * MB85RC256V — 256 Kbit (32 KiB) I²C FRAM (minimal driver).
 *
 * <p>Ferroelectric RAM: non-volatile like EEPROM, but every write completes at
 * bus speed — no write cycle, no page buffer, no ACK polling. The chip has no
 * configuration registers; it is ready for use immediately after power-on.
 *
 * <p>I²C address 0x50-0x57, set by the A2:A0 pins. The memory address is two
 * bytes (15 bits, big-endian), so the connection must be created with
 * {@code regBytes} = 2.
 *
 * <h2>Memory layout</h2>
 * <ul>
 *   <li>0x0000-0x7FFF — 32,768 bytes of FRAM</li>
 * </ul>
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>The full 32 KiB is addressable as raw bytes</li>
 *   <li>{@link #writeByte(int, int)} returns as soon as the transfer completes</li>
 *   <li>Writes are ignored by the chip while the WP pin is high (hardware only)</li>
 * </ul>
 */
public class MB85RCMinimal {

    protected static final int SIZE = 0x8000;

    protected final RegisterConnection connection;

    /**
     * Construct the driver. No I²C traffic occurs at construction.
     *
     * @param connection I²C connection with {@code regBytes} = 2, bound to 0x50-0x57
     */
    public MB85RCMinimal(RegisterConnection connection) {
        this.connection = connection;
    }

    /**
     * Validate a single address.
     *
     * @param address memory address
     * @throws IllegalArgumentException if outside 0-32767
     */
    protected static void checkAddress(int address) {
        if (address < 0 || address >= SIZE) {
            throw new IllegalArgumentException("address out of range 0-32767");
        }
    }

    /**
     * Read a single byte (random read).
     *
     * @param address memory address 0-32767
     * @return byte value 0-255
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if the address is out of range
     */
    public int readByte(int address) throws IOException {
        checkAddress(address);
        return connection.read(address, 1)[0] & 0xFF;
    }

    /**
     * Write a single byte; returns when the transfer completes (no polling needed).
     *
     * @param address memory address 0-32767
     * @param value   byte value 0-255
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if the address is out of range
     */
    public void writeByte(int address, int value) throws IOException {
        checkAddress(address);
        connection.write(address, new byte[]{(byte) value});
    }
}
