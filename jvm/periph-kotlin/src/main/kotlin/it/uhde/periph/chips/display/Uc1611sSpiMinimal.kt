package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn

/**
 * UC1611s over 4-wire SPI — minimal driver.
 *
 * Same framebuffer, drawing and initialisation as [Uc1611sMinimal], but the module is driven through a display
 * connection (CS#, D/C# = CD, RES# = RST optional). The module must be strapped for 4-wire SPI (BM0 = D13 = VSS),
 * SPI mode 0, up to 8 MHz. Every command byte, including the arguments of two- and three-byte commands, is sent with
 * D/C# low as its own CS window; RAM data goes out with D/C# high. There is no read-back and no acknowledge: nothing
 * is retried ([retryCount] stays 0), the driver waits a fixed 10 ms for the charge pump after reset or wake instead
 * of polling, and `readStatus()` / `readRam()` of [Uc1611sSpiFull] throw [UnsupportedOperationException].
 *
 * @param connection display connection
 * @param width columns, 1..240
 * @param height rows, a multiple of 8, 8..128
 */
class Uc1611sSpiMinimal(
    connection: DisplayConn,
    width: Int = WIDTH,
    height: Int = HEIGHT,
) : Uc1611sMinimal(Uc1611sSpiBus(connection), width, height, false, 0x8F)
