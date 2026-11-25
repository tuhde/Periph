package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn

/**
 * SH1106 over 4-wire SPI — minimal driver.
 *
 * Same framebuffer, drawing and initialisation as [SH1106Minimal], driven through a display connection (CS, D/C = A0,
 * RES optional, no BUSY). The chip must be strapped for 4-wire SPI (IM0 = IM1 = IM2 = 0), SPI mode 0, up to 2 MHz.
 * Every command byte, including arguments, is sent with D/C low; RAM data with D/C high. SPI cannot read:
 * `readStatus()` / `readPage()` of [SH1106SpiFull] throw [UnsupportedOperationException].
 *
 * @param connection display connection
 * @param width visible columns, 1..132
 * @param height rows, a multiple of 8, 8..64
 * @param columnOffset first RAM column shown (2 for 128-pixel modules)
 * @param internalDcdc true for the internal charge pump
 */
class SH1106SpiMinimal(
    connection: DisplayConn,
    width: Int = 128,
    height: Int = HEIGHT,
    columnOffset: Int = 2,
    internalDcdc: Boolean = true,
) : SH1106Minimal(SH1106SpiBus(connection), width, height, columnOffset, internalDcdc)
