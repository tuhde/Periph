package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn

/**
 * SSD1305 over 4-wire SPI — full driver (see [SSD1305Full]; `readStatus()` and `readPage()` throw
 * [UnsupportedOperationException] because SPI is write-only).
 *
 * @param connection display connection
 * @param width visible columns, 1..132
 * @param height rows, a multiple of 8, 16..64
 * @param columnOffset first RAM column shown (4 for 128-pixel modules)
 */
class SSD1305SpiFull(
    connection: DisplayConn,
    width: Int = 128,
    height: Int = HEIGHT,
    columnOffset: Int = 4,
) : SSD1305Full(SSD1305SpiBus(connection), width, height, columnOffset)
