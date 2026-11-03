package it.uhde.periph.chips.display

/**
 * Transport of the UC1611s drivers: one attempt per call, no retry (the driver retries).
 *
 * <p>Implemented by {@link Uc1611sI2cBus} (command and data address on one I2C bus) and {@link Uc1611sSpiBus}
 * (a 4-wire SPI display connection).
 */
interface Uc1611sBus {
    /** Send bytes as one command transfer. */
    void writeCommand(byte[] bytes) throws IOException

    /** Send {@code len} RAM data bytes of {@code buf} starting at {@code off}, in chunks if the transport needs it. */
    void writeData(byte[] buf, int off, int len) throws IOException

    /** @return true if status and RAM can be read back (I2C); the SPI interface of the module is write-only */
    boolean canRead()

    /** Write a NOP, then read Status1..3. */
    byte[] readStatus() throws IOException

    /** Read {@code n + 1} RAM bytes and return the {@code n} bytes after the dummy byte. */
    byte[] readRam(int n) throws IOException

    /** Pulse the hardware reset line, if any. */
    void hardwareReset() throws IOException
}
