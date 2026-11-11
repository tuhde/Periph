package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.DisplayConn

/**
 * SH1106 over 4-wire SPI — full driver (see {@link SH1106Full}; {@code readStatus()} and {@code readPage()} throw
 * {@link UnsupportedOperationException} because SPI is write-only).
 */
@CompileStatic
class SH1106SpiFull extends SH1106Full {

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
    SH1106SpiFull(DisplayConn connection, int width, int height, int columnOffset, boolean internalDcdc)
            throws IOException {
        super(new SH1106SpiBus(connection), width, height, columnOffset, internalDcdc)
    }

    /** Construct with the defaults 128 x 64, column offset 2 and the internal DC-DC. */
    SH1106SpiFull(DisplayConn connection) throws IOException {
        this(connection, 128, HEIGHT, 2, true)
    }
}
