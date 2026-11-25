package it.uhde.periph.chips.display

import groovy.transform.CompileStatic

import it.uhde.periph.connection.DisplayConn

import java.io.IOException

/** SSD1306 over 4-wire SPI — full driver (see {@link SSD1306Full}). */
@CompileStatic
class SSD1306SpiFull extends SSD1306Full {

    /**
     * Construct the driver and run the initialisation sequence (about 100 ms).
     *
     * @param connection  display connection
     * @param width       visible columns, 1..128
     * @param height      rows, a multiple of 8, 16..64
     * @param externalVcc true when the board supplies VCC (the charge pump stays off)
     * @throws IllegalArgumentException if an argument is out of range
     * @throws IOException              if a transfer fails
     */
    SSD1306SpiFull(DisplayConn connection, int width, int height, boolean externalVcc) throws IOException {
        super(new MonoOledSpiBus(connection), width, height, externalVcc)
    }

    /** Construct with the defaults 128 x 64 and the internal charge pump. */
    SSD1306SpiFull(DisplayConn connection) throws IOException {
        this(connection, 128, HEIGHT, false)
    }
}
