package it.uhde.periph.chips.display

/**
 * Transport hooks shared by the monochrome page-organised OLED controller drivers (SSD130x style).
 *
 * Implemented by [MonoOledI2cBus] (control-byte framing on one I2C connection) and [MonoOledSpiBus] (a display
 * connection; D/C low for commands, high for RAM data).
 */
interface MonoOledBus {
    /** Send bytes as one command stream. */
    fun writeCommands(bytes: ByteArray)

    /** Send [len] RAM data bytes of [buf] starting at [off]. */
    fun writeData(buf: ByteArray, off: Int, len: Int)

    /** Pulse the hardware reset line, if any (no-op for I2C). */
    fun hardwareReset() {}
}
