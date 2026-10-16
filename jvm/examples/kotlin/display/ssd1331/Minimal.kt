///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.DisplayConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.connection.WireMode
import it.uhde.periph.chips.display.Ssd1331Minimal

// SSD1331 minimal example: Waveshare 0.95inch RGB OLED on a Raspberry Pi.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17 (no BUSY line).
// Minimal use: eight colour bars and a white frame, sent once.

fun main() {
    val dc = SysfsOutputPin(25)                                                                                 // create D/C# pin, (gpio=25) → OutputPin
    val res = SysfsOutputPin(17)                                                                                // create RES# pin, (gpio=17) → OutputPin
    val connection = DisplayConnection.hardware(0, 0, 4000000, dc, res, null, WireMode.SIMPLEX)                 // open display connection, (bus, device, speedHz, dc, res, busy=null, wireMode)
    val oled = Ssd1331Minimal(connection)                                                                       // construct driver, (connection) → Ssd1331Minimal
    val bars = intArrayOf(Ssd1331Minimal.WHITE, Ssd1331Minimal.YELLOW, Ssd1331Minimal.CYAN, Ssd1331Minimal.GREEN, Ssd1331Minimal.MAGENTA, Ssd1331Minimal.RED, Ssd1331Minimal.BLUE, Ssd1331Minimal.BLACK)
    for (y in 0 until 64) {
        for (x in 0 until 96) {
            oled.setPixel(x, y, bars[x / 12])                                                                   // set one pixel, (x, y, color)
        }
    }
    for (x in 0 until 96) {
        oled.setPixel(x, 0, Ssd1331Minimal.WHITE)                                                               // set one pixel, (x, y, color)
        oled.setPixel(x, 63, Ssd1331Minimal.WHITE)                                                              // set one pixel, (x, y, color)
    }
    oled.show()                                                                                                 // send framebuffer, () → void
    connection.close()
}
