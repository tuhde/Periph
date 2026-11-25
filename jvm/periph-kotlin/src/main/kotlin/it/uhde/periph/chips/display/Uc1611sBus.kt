package it.uhde.periph.chips.display

/**
 * Transport of the UC1611s drivers: one attempt per call, no retry (the driver retries).
 *
 * Implemented by [Uc1611sI2cBus] (command and data address on one I2C bus) and [Uc1611sSpiBus] (a 4-wire SPI
 * display connection).
 */
interface Uc1611sBus {
    /** Send bytes as one command transfer. */
    fun writeCommand(bytes: ByteArray)

    /** Send [len] RAM data bytes of [buf] starting at [off], in chunks if the transport needs it. */
    fun writeData(buf: ByteArray, off: Int, len: Int)

    /** @return true if status and RAM can be read back (I2C); the SPI interface of the module is write-only */
    fun canRead(): Boolean

    /** Write a NOP, then read Status1..3. */
    fun readStatus(): ByteArray = throw UnsupportedOperationException("read-back is not available on this transport")

    /** Read `n + 1` RAM bytes and return the `n` bytes after the dummy byte. */
    fun readRam(n: Int): ByteArray = throw UnsupportedOperationException("read-back is not available on this transport")

    /** Pulse the hardware reset line, if any. */
    fun hardwareReset() {}
}
