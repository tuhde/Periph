package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.DisplayConn

/**
 * SH1106 over 4-wire SPI — minimal driver.
 *
 * <p>Same framebuffer, drawing and initialisation as {@link SH1106Minimal}, driven through a display connection
 * (CS, D/C = A0, RES optional, no BUSY). The chip must be strapped for 4-wire SPI (IM0 = IM1 = IM2 = 0), SPI mode 0,
 * up to 2 MHz. Every command byte, including arguments, is sent with D/C low; RAM data with D/C high. SPI cannot
 * read: {@code readStatus()} / {@code readPage()} of {@link SH1106SpiFull} throw {@link UnsupportedOperationException}.
 */
@CompileStatic
class SH1106SpiMinimal extends SH1106Minimal {

    /**
     * Construct the driver and run the initialisation sequence (about 250 ms).
     *
     * @param connection   display connection
     * @param width        visible columns, 1..132
     * @param height       rows, a multiple of 8, 8..64
     * @param columnOffset first RAM column shown (2 for 128-pixel modules)
     * @param internalDcdc true for the internal charge pump
     * @throws IllegalArgumentException if an argument is out of range
     * @throws IOException              if a transfer fails
     */
    SH1106SpiMinimal(DisplayConn connection, int width, int height, int columnOffset, boolean internalDcdc)
            throws IOException {
        super(new SH1106SpiBus(connection), width, height, columnOffset, internalDcdc)
    }

    /** Construct with the defaults 128 x 64, column offset 2 and the internal DC-DC. */
    SH1106SpiMinimal(DisplayConn connection) throws IOException {
        this(connection, 128, HEIGHT, 2, true)
    }
}
