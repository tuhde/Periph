package it.uhde.periph.connection;

import java.io.IOException;

/**
 * Optional capability a {@link Connection} implementation may support: a
 * write variant that appends a caller-chosen number of trailing zero bytes
 * <em>after</em> whatever bit-encoding it applies, instead of a fixed
 * default.
 *
 * <p>{@link NeoPixelConnection} implements this so chips needing a longer
 * minimum reset pulse than WS2812B's default (e.g. SK6812RGBW's ≥80 µs vs.
 * the connection's default ≈53 µs) can request one. Padding the
 * <em>pre-encoded</em> data buffer with extra zero bytes instead does not
 * achieve this: those bytes get bit-encoded as more zero-value data bits,
 * which is a periodic low-with-brief-highs, not the continuous low a reset
 * pulse actually requires — check for this interface via {@code instanceof}
 * rather than assuming every {@link Connection} accepts a longer reset.
 */
public interface ResetExtender {

    /**
     * Encode and transmit {@code data}, then hold the line low for
     * {@code resetBytes} trailing zero bytes instead of the implementation's
     * default. No-op if the connection is disabled.
     *
     * @param data       bytes to write
     * @param resetBytes trailing zero bytes to append after encoding
     * @throws IOException on bus error
     */
    void writeExt(byte[] data, int resetBytes) throws IOException;
}
