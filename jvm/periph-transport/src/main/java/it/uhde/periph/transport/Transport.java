package it.uhde.periph.transport;

import java.io.IOException;

/**
 * Represents one device on a bus. Each instance is bound to a single device address.
 * Implementations wrap a platform-specific bus (e.g. Linux i2c-dev via FFM).
 */
public interface Transport extends AutoCloseable {

    /**
     * Send bytes to the device.
     *
     * @param data bytes to write
     * @throws IOException on bus error or no ACK
     */
    void write(byte[] data) throws IOException;

    /**
     * Read bytes from the device.
     *
     * @param n number of bytes to read
     * @return bytes received
     * @throws IOException on bus error or no ACK
     */
    byte[] read(int n) throws IOException;

    /**
     * Write then read without releasing the bus between phases.
     *
     * @param data bytes to write (typically a register address)
     * @param n    number of bytes to read back
     * @return bytes received
     * @throws IOException on bus error or no ACK
     */
    byte[] writeRead(byte[] data, int n) throws IOException;

    @Override
    void close() throws IOException;
}
