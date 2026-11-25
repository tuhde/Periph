package it.uhde.periph.chips.display

/**
 * Transport of the SSD1305 drivers.
 *
 * Implemented by [SSD1305I2cBus] (control-byte framing on one I2C address) and [SSD1305SpiBus] (a 4-wire SPI display
 * connection).
 */
interface SSD1305Bus {
    /** Send bytes as one command stream. */
    fun writeCommands(bytes: ByteArray)

    /** Send [len] RAM data bytes of [buf] starting at [off], in chunks if the transport needs it. */
    fun writeData(buf: ByteArray, off: Int, len: Int)

    /** @return true if status and RAM can be read back (I2C); the SPI interface is write-only */
    fun canRead(): Boolean

    /** Read the status byte. */
    fun readStatus(): Int = throw UnsupportedOperationException("read-back is not available on this transport")

    /** Read `n + 1` RAM bytes and return the `n` bytes after the dummy byte. */
    fun readRam(n: Int): ByteArray = throw UnsupportedOperationException("read-back is not available on this transport")

    /** Pulse the hardware reset line, if any. */
    fun hardwareReset() {}
}
