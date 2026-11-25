package it.uhde.periph.chips.display;

import it.uhde.periph.connection.DisplayConn;

import java.io.IOException;

/**
 * UC1611s over 4-wire SPI — minimal driver.
 *
 * <p>Same framebuffer, drawing and initialisation as {@link Uc1611sMinimal}, but the module is driven through a
 * display connection (CS#, D/C# = CD, RES# = RST optional). The module must be strapped for 4-wire SPI
 * (BM0 = D13 = VSS), SPI mode 0, up to 8 MHz. Every command byte, including the arguments of two- and three-byte
 * commands, is sent with D/C# low as its own CS window; RAM data goes out with D/C# high. There is no read-back and
 * no acknowledge: nothing is retried ({@link #retryCount()} stays 0), the driver waits a fixed 10 ms for the charge
 * pump after reset or wake instead of polling, and {@code readStatus()} / {@code readRam()} of
 * {@link Uc1611sSpiFull} throw {@link UnsupportedOperationException}.
 */
public class Uc1611sSpiMinimal extends Uc1611sMinimal {

    /**
     * Construct the driver and run the initialisation sequence (about 400 ms).
     *
     * @param connection display connection
     * @param width      columns, 1..240
     * @param height     rows, a multiple of 8, 8..128
     * @throws IllegalArgumentException if width or height is out of range
     * @throws IOException              if a transfer fails
     */
    public Uc1611sSpiMinimal(DisplayConn connection, int width, int height) throws IOException {
        super(new Uc1611sSpiBus(connection), width, height, false, 0x8F);
    }

    /** Construct with the default size 240 x 128. */
    public Uc1611sSpiMinimal(DisplayConn connection) throws IOException {
        this(connection, WIDTH, HEIGHT);
    }
}
