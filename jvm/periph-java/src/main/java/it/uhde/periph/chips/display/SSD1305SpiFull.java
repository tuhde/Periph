package it.uhde.periph.chips.display;

import it.uhde.periph.connection.DisplayConn;

import java.io.IOException;

/**
 * SSD1305 over 4-wire SPI — full driver (see {@link SSD1305Full}; {@code readStatus()} and {@code readPage()} throw
 * {@link UnsupportedOperationException} because SPI is write-only).
 */
public class SSD1305SpiFull extends SSD1305Full {

    /**
     * Construct the driver and run the initialisation sequence (about 100 ms).
     *
     * @param connection   display connection
     * @param width        visible columns, 1..132
     * @param height       rows, a multiple of 8, 16..64
     * @param columnOffset first RAM column shown (4 for 128-pixel modules)
     * @throws IllegalArgumentException if an argument is out of range
     * @throws IOException              if a transfer fails
     */
    public SSD1305SpiFull(DisplayConn connection, int width, int height, int columnOffset)
            throws IOException {
        super(new SSD1305SpiBus(connection), width, height, columnOffset);
    }

    /** Construct with the defaults 128 x 64, column offset 4. */
    public SSD1305SpiFull(DisplayConn connection) throws IOException {
        this(connection, 128, HEIGHT, 4);
    }
}
