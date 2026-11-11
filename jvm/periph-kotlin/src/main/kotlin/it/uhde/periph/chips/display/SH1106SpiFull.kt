package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn

/**
 * SH1106 over 4-wire SPI — full driver (see [SH1106Full]; `readStatus()` and `readPage()` throw
 * [UnsupportedOperationException] because SPI is write-only).
 *
 * @param connection display connection
 * @param width visible columns, 1..132
 * @param height rows, a multiple of 8, 8..64
 * @param columnOffset first RAM column shown (2 for 128-pixel modules)
 * @param internalDcdc true for the internal charge pump
 */
class SH1106SpiFull(
    connection: DisplayConn,
    width: Int = 128,
    height: Int = HEIGHT,
    columnOffset: Int = 2,
    internalDcdc: Boolean = true,
) : SH1106Full(SH1106SpiBus(connection), width, height, columnOffset, internalDcdc)
