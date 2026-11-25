package it.uhde.periph.chips.display

/**
 * Transport of the SSD1305 drivers.
 *
 * <p>Implemented by {@link SSD1305I2cBus} (control-byte framing on one I2C address) and {@link SSD1305SpiBus}
 * (a 4-wire SPI display connection).
 */
interface SSD1305Bus {
    /** Send bytes as one command stream. */
    void writeCommands(byte[] bytes) throws IOException

    /** Send {@code len} RAM data bytes of {@code buf} starting at {@code off}, in chunks if the transport needs it. */
    void writeData(byte[] buf, int off, int len) throws IOException

    /** @return true if status and RAM can be read back (I2C); the SPI interface is write-only */
    boolean canRead()

    /** Read the status byte. */
    int readStatus() throws IOException

    /** Read {@code n + 1} RAM bytes and return the {@code n} bytes after the dummy byte. */
    byte[] readRam(int n) throws IOException

    /** Pulse the hardware reset line, if any. */
    void hardwareReset() throws IOException
}
