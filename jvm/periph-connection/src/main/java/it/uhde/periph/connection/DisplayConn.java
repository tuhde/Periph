package it.uhde.periph.connection;

import java.io.IOException;

/**
 * A connection to a display controller (OLED: SH1106; e-paper: UC8276C,
 * SSD1683): SPI plus D/C#, optional RES# and optional BUSY. See {@code
 * specs/transport_display.md}.
 *
 * <p>Implemented by {@link DisplayConnection} and {@link
 * MockDisplayConnection}; chip drivers depend on this interface so they can be
 * unit-tested with no hardware. They never touch D/C, RES, BUSY or CS
 * themselves. The BUSY polarity is chip-level knowledge, so {@link #waitBusy}
 * takes it as an argument.
 */
public interface DisplayConn extends AutoCloseable {

    /**
     * Hardware reset: RES# low for the pulse width, then high and wait the
     * settle time. Does not wait for BUSY. No-op when disabled or no RES# pin.
     *
     * @throws IOException on a pin error
     */
    void reset() throws IOException;

    /**
     * Raw level of the BUSY line.
     *
     * @return the level, or {@code false} when no BUSY pin is wired
     * @throws IOException if the line cannot be read
     */
    boolean busyLevel() throws IOException;

    /**
     * Poll BUSY (every millisecond) until it is no longer active.
     *
     * @param busyActiveHigh {@code true} if the controller drives BUSY high
     *                       while busy (SSD1683), {@code false} if low (UC8276C
     *                       BUSY_N)
     * @param timeoutMs      give up after this many milliseconds; {@code <= 0}
     *                       waits forever
     * @return {@code true} when the controller became ready (or no BUSY pin is
     *         wired), {@code false} on timeout
     * @throws IOException if BUSY cannot be read or the wait is interrupted
     */
    boolean waitBusy(boolean busyActiveHigh, long timeoutMs) throws IOException;

    /**
     * Send a command byte (D/C# low), then optional parameter bytes (D/C#
     * high), as separate SPI transfers. No-op when disabled.
     *
     * @param cmd  command byte (0&ndash;255)
     * @param data parameter bytes; may be empty
     * @throws IOException on a bus error
     */
    void sendCommand(int cmd, byte[] data) throws IOException;

    /**
     * Send data bytes (D/C# high) as one SPI transfer. No-op when disabled.
     *
     * @param data bytes to send, e.g. a pixel plane
     * @throws IOException on a bus error
     */
    void sendData(byte[] data) throws IOException;

    /**
     * Read bytes the controller drives after a read command (D/C# high), one
     * CS window per byte.
     *
     * @param length number of bytes
     * @return the bytes read (zeros when disabled)
     * @throws UnsupportedOperationException in {@link WireMode#SIMPLEX}
     * @throws IOException on a bus error
     */
    byte[] readData(int length) throws IOException;

    /** Resume operation (and assert the EN pin, if wired). */
    void enable();

    /** Gate all bus operations (and de-assert the EN pin, if wired). */
    void disable();

    /** @return the current software-gate state */
    boolean isEnabled();

    @Override
    void close() throws IOException;
}
