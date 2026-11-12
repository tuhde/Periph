package it.uhde.periph.chips.display;

import java.io.IOException;

/**
 * Transport hooks shared by the monochrome page-organised OLED controller drivers (SSD130x style).
 *
 * <p>Implemented by {@link MonoOledI2cBus} (control-byte framing on one I2C connection) and
 * {@link MonoOledSpiBus} (a display connection; D/C low for commands, high for RAM data).
 */
public interface MonoOledBus {

    /**
     * Send bytes as one command stream.
     *
     * @param bytes command and argument bytes
     * @throws IOException if a transfer fails
     */
    void writeCommands(byte[] bytes) throws IOException;

    /**
     * Send RAM data bytes.
     *
     * @param buf buffer
     * @param off first byte
     * @param len number of bytes
     * @throws IOException if a transfer fails
     */
    void writeData(byte[] buf, int off, int len) throws IOException;

    /**
     * Pulse the hardware reset line, if any (no-op for I2C).
     *
     * @throws IOException if the reset fails
     */
    default void hardwareReset() throws IOException {
    }
}
