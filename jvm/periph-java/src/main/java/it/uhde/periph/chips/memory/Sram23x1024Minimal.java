package it.uhde.periph.chips.memory;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * 23LC1024 / 23A1024 — 1-Mbit SPI serial SRAM (minimal driver).
 *
 * <p>Byte read/write access to the 131,072-byte array over plain SPI (Mode 0).
 * The chip is volatile but has unlimited cycles and zero write time: a write is
 * complete when CS is released, with no polling.
 *
 * <p>Default configuration: Sequential mode (MODE register = 0x40), written and
 * read back at construction; a mismatch (floating/missing MISO, wrong SPI mode,
 * chip stuck in SDI/SQI mode) throws an {@code IOException}. The full 128 KiB is
 * addressable; SDI/SQI bus modes are never used.
 *
 * <p>Every transaction is one instruction byte, a 24-bit big-endian address and
 * data, with CS held low for the whole frame, so the driver takes the bare
 * {@code Connection} and uses {@code write}/{@code writeRead} directly (the
 * instruction is not a register address, so {@code RegisterConnection}'s read-bit
 * logic does not apply).
 */
public class Sram23x1024Minimal {

    /** Array size in bytes. */
    public static final int SIZE = 131072;

    protected static final int CMD_READ  = 0x03;
    protected static final int CMD_WRITE = 0x02;
    protected static final int CMD_RDMR  = 0x05;
    protected static final int CMD_WRMR  = 0x01;
    protected static final int CMD_RSTIO = 0xFF;
    protected static final int MODE_MASK = 0xC0;

    protected final Connection connection;

    /**
     * Construct the driver and run the initialization sequence: write MODE=0x40
     * (Sequential) and verify it with RDMR.
     *
     * @param connection SPI connection (Mode 0, MSB first, CS managed by the connection)
     * @throws IOException on bus error or a MODE register mismatch
     */
    public Sram23x1024Minimal(Connection connection) throws IOException {
        this.connection = connection;
        writeMode(0x40);
        int mode = readMode();
        if (mode != 0x40) {
            throw new IOException(String.format("23X1024 MODE register 0x%02X, expected 0x40 (check wiring and SPI Mode 0)", mode));
        }
    }

    protected int readMode() throws IOException {
        return connection.writeRead(new byte[] {(byte) CMD_RDMR}, 1)[0] & 0xFF;
    }

    protected void writeMode(int mode) throws IOException {
        connection.write(new byte[] {(byte) CMD_WRMR, (byte) mode});
    }

    private static byte[] header(int command, int address) {
        return new byte[] {(byte) command, (byte) (address >> 16), (byte) (address >> 8), (byte) address};
    }

    protected byte[] readRaw(int address, int length) throws IOException {
        return connection.writeRead(header(CMD_READ, address), length);
    }

    protected void writeRaw(int address, byte[] data, int offset, int length) throws IOException {
        byte[] frame = new byte[4 + length];
        System.arraycopy(header(CMD_WRITE, address), 0, frame, 0, 4);
        System.arraycopy(data, offset, frame, 4, length);
        connection.write(frame);
    }

    /**
     * Read one byte (READ, 0x03).
     *
     * @param address 0-131071
     * @return byte value 0-255
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the address is out of range
     */
    public int readByte(int address) throws IOException {
        if (address < 0 || address >= SIZE) throw new IllegalArgumentException("address out of range");
        return readRaw(address, 1)[0] & 0xFF;
    }

    /**
     * Write one byte (WRITE, 0x02); complete when CS is released.
     *
     * @param address 0-131071
     * @param value   byte value 0-255
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the address is out of range
     */
    public void writeByte(int address, int value) throws IOException {
        if (address < 0 || address >= SIZE) throw new IllegalArgumentException("address out of range");
        writeRaw(address, new byte[] {(byte) value}, 0, 1);
    }
}
