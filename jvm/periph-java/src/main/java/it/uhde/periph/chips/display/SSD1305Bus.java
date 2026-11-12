package it.uhde.periph.chips.display;

import java.io.IOException;

/**
 * Transport of the SSD1305 drivers.
 *
 * <p>Implemented by {@link SSD1305I2cBus} (control-byte framing on one I2C address) and {@link SSD1305SpiBus}
 * (a 4-wire SPI display connection).
 */
public interface SSD1305Bus {
    /** Send bytes as one command stream. */
    void writeCommands(byte[] bytes) throws IOException;

    /** Send {@code len} RAM data bytes of {@code buf} starting at {@code off}, in chunks if the transport needs it. */
    void writeData(byte[] buf, int off, int len) throws IOException;

    /** @return true if status and RAM can be read back (I2C); the SPI interface is write-only */
    boolean canRead();

    /** Read the status byte. */
    default int readStatus() throws IOException {
        throw new UnsupportedOperationException("read-back is not available on this transport");
    }

    /** Read {@code n + 1} RAM bytes and return the {@code n} bytes after the dummy byte. */
    default byte[] readRam(int n) throws IOException {
        throw new UnsupportedOperationException("read-back is not available on this transport");
    }

    /** Pulse the hardware reset line, if any. */
    default void hardwareReset() throws IOException {
    }
}
