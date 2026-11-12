package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn

/**
 * SSD1306 over 4-wire SPI — minimal driver.
 *
 * Same framebuffer, drawing and initialisation as [SSD1306Minimal], driven through a display connection (CS, D/C,
 * RES optional, no BUSY). The chip must be strapped for 4-wire SPI (BS0 = BS1 = BS2 = 0), SPI mode 0, up to 10 MHz
 * (4 MHz recommended). Every command byte, including arguments, is sent with D/C low; RAM data with D/C high.
 *
 * @param connection display connection
 * @param width visible columns, 1..128
 * @param height rows, a multiple of 8, 16..64
 * @param externalVcc true when the board supplies VCC (the charge pump stays off)
 */
class SSD1306SpiMinimal(
    connection: DisplayConn,
    width: Int = 128,
    height: Int = HEIGHT,
    externalVcc: Boolean = false,
) : SSD1306Minimal(MonoOledSpiBus(connection), width, height, externalVcc)
