package it.uhde.periph.transport;

import java.io.IOException;
import java.util.Arrays;

/**
 * SMBus transport for Linux (wraps {@link I2CTransport} with address validation and PEC).
 *
 * <p>Enforces the valid 7-bit SMBus address range (0x08–0x77) and, when {@code pec} is
 * true, appends a CRC-8 byte to writes and verifies it on reads. Built on {@link I2CTransport},
 * so {@link #writeRead} performs a stop-then-start rather than a true repeated start.
 */
public final class SMBusTransport implements Transport {

    private final I2CTransport i2c;
    private final int address;
    private final boolean pec;

    /**
     * Open an SMBus device.
     *
     * @param bus     I²C bus number (e.g. 1 for /dev/i2c-1)
     * @param address 7-bit device address (0x08–0x77)
     * @param pec     enable Packet Error Code (CRC-8) checking
     * @throws IOException if address is outside the valid SMBus range, or the device cannot be opened
     */
    public SMBusTransport(int bus, int address, boolean pec) throws IOException {
        if (address < 0x08 || address > 0x77) {
            throw new IOException("SMBus address must be in range 0x08-0x77");
        }
        this.address = address;
        this.pec = pec;
        this.i2c = new I2CTransport(bus, address);
    }

    private static int crc8(byte[] data, int crc) {
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc;
    }

    /**
     * Send bytes to the device, appending a PEC byte if enabled.
     *
     * @param data bytes to write
     * @throws IOException on bus error or no ACK
     */
    @Override
    public void write(byte[] data) throws IOException {
        if (!pec) {
            i2c.write(data);
            return;
        }
        byte[] buf = Arrays.copyOf(data, data.length + 1);
        int crc = crc8(new byte[] {(byte) (address << 1)}, 0);
        crc = crc8(data, crc);
        buf[data.length] = (byte) crc;
        i2c.write(buf);
    }

    /**
     * Read bytes from the device, verifying the PEC byte if enabled.
     *
     * <p>Reads n+1 bytes when PEC is enabled and strips the trailing CRC byte.
     *
     * @param n number of data bytes to read
     * @return the n data bytes (PEC byte stripped if enabled)
     * @throws IOException on bus error, no ACK, or PEC mismatch
     */
    @Override
    public byte[] read(int n) throws IOException {
        if (!pec) {
            return i2c.read(n);
        }
        byte[] raw = i2c.read(n + 1);
        byte[] data = Arrays.copyOf(raw, n);
        int crc = crc8(new byte[] {(byte) ((address << 1) | 1)}, 0);
        crc = crc8(data, crc);
        if (crc != (raw[n] & 0xFF)) {
            throw new IOException("SMBus PEC error");
        }
        return data;
    }

    /**
     * Write then read, with PEC covering the full transaction if enabled.
     *
     * <p>PEC covers write address + write data + read address + read data.
     *
     * @param data bytes to write (typically a register address)
     * @param n    number of data bytes to read back
     * @return the n data bytes (PEC byte stripped if enabled)
     * @throws IOException on bus error, no ACK, or PEC mismatch
     */
    @Override
    public byte[] writeRead(byte[] data, int n) throws IOException {
        if (!pec) {
            return i2c.writeRead(data, n);
        }
        byte[] raw = i2c.writeRead(data, n + 1);
        byte[] result = Arrays.copyOf(raw, n);
        int crc = crc8(new byte[] {(byte) (address << 1)}, 0);
        crc = crc8(data, crc);
        crc = crc8(new byte[] {(byte) ((address << 1) | 1)}, crc);
        crc = crc8(result, crc);
        if (crc != (raw[n] & 0xFF)) {
            throw new IOException("SMBus PEC error");
        }
        return result;
    }

    /**
     * Close the underlying I²C device.
     *
     * @throws IOException on close error
     */
    @Override
    public void close() throws IOException {
        i2c.close();
    }
}
