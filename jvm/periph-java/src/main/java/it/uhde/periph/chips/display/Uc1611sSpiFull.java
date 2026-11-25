package it.uhde.periph.chips.display;

import it.uhde.periph.connection.DisplayConn;

import java.io.IOException;

/**
 * UC1611s over 4-wire SPI — full driver (see {@link Uc1611sFull}; {@code readStatus()} and {@code readRam()} throw
 * {@link UnsupportedOperationException} because the module's SPI is write-only).
 */
public class Uc1611sSpiFull extends Uc1611sFull {

    /**
     * Construct the driver and run the initialisation sequence (about 400 ms).
     *
     * @param connection display connection
     * @param width      columns, 1..240
     * @param height     rows, a multiple of 8, 8..128
     * @param topView    true for the 12:00 viewing direction (MY = 1, MX = 0)
     * @param contrast   potentiometer PM 0..255 (the DOG example value is 0x8F)
     * @throws IllegalArgumentException if width or height is out of range
     * @throws IOException              if a transfer fails
     */
    public Uc1611sSpiFull(DisplayConn connection, int width, int height, boolean topView, int contrast)
            throws IOException {
        super(new Uc1611sSpiBus(connection), width, height, topView, contrast);
    }

    /** Construct with the default size 240 x 128, bottom view and contrast 0x8F. */
    public Uc1611sSpiFull(DisplayConn connection) throws IOException {
        this(connection, WIDTH, HEIGHT, false, 0x8F);
    }
}
