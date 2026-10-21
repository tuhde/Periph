package it.uhde.periph.connection;

import java.io.IOException;

/**
 * Optional SPI operations beyond the {@link Connection} contract
 * (specs/transport_spi.md, "Optional Extensions"), required by SD cards in SPI
 * mode. An SPI connection that implements them satisfies this interface; chips
 * that do not need them never call them.
 *
 * <p>{@code read}, {@code readInto} and {@code writeRead} clock {@code 0xFF} on
 * MOSI during the read phase.
 */
public interface SPIExtensions extends Connection {

    /**
     * Clock {@code n} bits of {@code 0xFF} with CS deasserted (rounded up to whole bytes).
     *
     * @param n number of clock bits
     * @throws UnsupportedOperationException if the connection has no GPIO chip select
     */
    void idleClocks(int n) throws IOException;

    /**
     * Change SCK to the highest supported frequency not above {@code hz}.
     *
     * @param hz clock frequency in Hz
     */
    void setSpeed(int hz) throws IOException;

    /**
     * Assert CS and hold it low across following {@code write}/{@code read}/{@code writeRead}
     * calls until {@link #deselect()}.
     *
     * @throws UnsupportedOperationException if the connection has no GPIO chip select
     */
    void select() throws IOException;

    /** Release CS asserted by {@link #select()}. */
    void deselect() throws IOException;

    /**
     * Fill {@code buf} with {@code buf.length} bytes read from the device.
     *
     * @param buf destination buffer
     */
    void readInto(byte[] buf) throws IOException;
}
