package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn

/**
 * UC1611s over 4-wire SPI — full driver (see [Uc1611sFull]; `readStatus()` and `readRam()` throw
 * [UnsupportedOperationException] because the module's SPI is write-only).
 *
 * @param connection display connection
 * @param width columns, 1..240
 * @param height rows, a multiple of 8, 8..128
 * @param topView true for the 12:00 viewing direction (MY = 1, MX = 0)
 * @param contrast potentiometer PM 0..255 (the DOG example value is 0x8F)
 */
class Uc1611sSpiFull(
    connection: DisplayConn,
    width: Int = WIDTH,
    height: Int = HEIGHT,
    topView: Boolean = false,
    contrast: Int = 0x8F,
) : Uc1611sFull(Uc1611sSpiBus(connection), width, height, topView, contrast)
