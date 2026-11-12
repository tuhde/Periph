package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn

/**
 * SSD1305 over 4-wire SPI — minimal driver.
 *
 * Same framebuffer, drawing and initialisation as [SSD1305Minimal], driven through a display connection (CS, D/C,
 * RES optional, no BUSY). The chip must be strapped for 4-wire SPI (BS0 = BS1 = BS2 = 0), SPI mode 0, up to 4 MHz
 * (2 MHz recommended).
 * Every command byte, including arguments, is sent with D/C low; RAM data with D/C high. SPI cannot read:
 * `readStatus()` / `readPage()` of [SSD1305SpiFull] throw [UnsupportedOperationException].
 *
 * @param connection display connection
 * @param width visible columns, 1..132
 * @param height rows, a multiple of 8, 16..64
 * @param columnOffset first RAM column shown (4 for 128-pixel modules)
 */
class SSD1305SpiMinimal(
    connection: DisplayConn,
    width: Int = 128,
    height: Int = HEIGHT,
    columnOffset: Int = 4,
) : SSD1305Minimal(SSD1305SpiBus(connection), width, height, columnOffset)
