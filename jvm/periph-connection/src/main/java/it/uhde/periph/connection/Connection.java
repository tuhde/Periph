package it.uhde.periph.connection;

import java.io.IOException;

/**
 * Represents one device on a bus. Each instance is bound to a single device address.
 * Implementations wrap a platform-specific bus (e.g. Linux i2c-dev via FFM) and
 * gate every read/write through a software enable flag, plus optional INT-line
 * ({@link InputPin}) and hardware EN-pin ({@link OutputPin}) support.
 */
public interface Connection extends AutoCloseable {

    /** Resume bus access; drives the hardware EN pin high if wired. */
    void enable();

    /**
     * Gate all subsequent bus reads and writes; drives EN pin low if wired.
     *
     * <p>Reads silently return zero-filled buffers; writes silently become no-ops.
     */
    void disable();

    /** @return the current software-gate state. */
    boolean isEnabled();

    /** @return the INT-line {@link InputPin}, or {@code null} if none is wired. */
    InputPin intPin();

    /** @return the EN-pin {@link OutputPin}, or {@code null} if none is wired. */
    OutputPin enPin();

    /**
     * Send bytes to the device, unless disabled.
     *
     * @param data bytes to write
     * @throws IOException on bus error or no ACK
     */
    void write(byte[] data) throws IOException;

    /**
     * Read bytes from the device, unless disabled.
     *
     * @param n number of bytes to read
     * @return bytes received, or {@code n} zero bytes if disabled
     * @throws IOException on bus error or no ACK
     */
    byte[] read(int n) throws IOException;

    /**
     * Write then read without releasing the bus between phases, unless disabled.
     *
     * @param data bytes to write (typically a register address)
     * @param n    number of bytes to read back
     * @return bytes received, or {@code n} zero bytes if disabled
     * @throws IOException on bus error or no ACK
     */
    byte[] writeRead(byte[] data, int n) throws IOException;

    @Override
    void close() throws IOException;
}
