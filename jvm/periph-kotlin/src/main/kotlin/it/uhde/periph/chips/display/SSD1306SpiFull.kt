package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn

/**
 * SSD1306 over 4-wire SPI — full driver (see [SSD1306Full]).
 *
 * @param connection display connection
 * @param width visible columns, 1..128
 * @param height rows, a multiple of 8, 16..64
 * @param externalVcc true when the board supplies VCC (the charge pump stays off)
 */
class SSD1306SpiFull(
    connection: DisplayConn,
    width: Int = 128,
    height: Int = HEIGHT,
    externalVcc: Boolean = false,
) : SSD1306Full(MonoOledSpiBus(connection), width, height, externalVcc)
