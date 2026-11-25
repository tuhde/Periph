package it.uhde.periph.chips.display;

import it.uhde.periph.connection.DisplayConn;

import java.io.IOException;

/**
 * SSD1306 over 4-wire SPI — minimal driver.
 *
 * <p>Same framebuffer, drawing and initialisation as {@link SSD1306Minimal}, driven through a display connection
 * (CS, D/C, RES optional, no BUSY). The chip must be strapped for 4-wire SPI (BS0 = BS1 = BS2 = 0), SPI mode 0,
 * up to 10 MHz (4 MHz recommended). Every command byte, including arguments, is sent with D/C low; RAM data with
 * D/C high.
 */
public class SSD1306SpiMinimal extends SSD1306Minimal {

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
    public SSD1306SpiMinimal(DisplayConn connection, int width, int height, boolean externalVcc) throws IOException {
        super(new MonoOledSpiBus(connection), width, height, externalVcc);
    }

    /** Construct with the defaults 128 x 64 and the internal charge pump. */
    public SSD1306SpiMinimal(DisplayConn connection) throws IOException {
        this(connection, 128, HEIGHT, false);
    }
}
