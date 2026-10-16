///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.DisplayConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.connection.WireMode
import it.uhde.periph.chips.display.Ssd1331Full

// SSD1331 demo example: Waveshare 0.95inch RGB OLED on a Raspberry Pi.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17 (no BUSY line).
// Demo: a colour test card — eight colour bars over a gray ramp, a frame, a marker drawn with the controller's own rectangle command, then a slow scroll.

fun main() {
    val dc = SysfsOutputPin(25)                                                                                 // create D/C# pin, (gpio=25) → OutputPin
    val res = SysfsOutputPin(17)                                                                                // create RES# pin, (gpio=17) → OutputPin
    val connection = DisplayConnection.hardware(0, 0, 4000000, dc, res, null, WireMode.SIMPLEX)                 // open display connection, (bus, device, speedHz, dc, res, busy=null, wireMode)
    val oled = Ssd1331Full(connection)                                                                          // construct driver, (connection) → Ssd1331Full

    // --- Draw the test card into the framebuffer ---
    // Eight 12-pixel colour bars over a 26-row gray ramp show that all channels and the
    // 5-6-5 depth work; the one-pixel frame shows that the whole 96 x 64 area is addressed.
    val bars = intArrayOf(Ssd1331Full.WHITE, Ssd1331Full.YELLOW, Ssd1331Full.CYAN, Ssd1331Full.GREEN, Ssd1331Full.MAGENTA, Ssd1331Full.RED, Ssd1331Full.BLUE, Ssd1331Full.BLACK)
    oled.clear(Ssd1331Full.BLACK)                                                                               // fill framebuffer, (color)
    for (x in 0 until 96) {
        oled.drawRect(x, 2, 1, 30, bars[x / 12], true)                                                          // draw rectangle, (x, y, w, h, color, fill)
        val g = x * 255 / 95
        oled.drawRect(x, 34, 1, 26, Ssd1331Full.rgb(g, g, g), true)                                             // draw rectangle, (x, y, w, h, color, fill)
    }
    oled.drawRect(0, 0, 96, 64, Ssd1331Full.WHITE, false)                                                       // draw rectangle, (x, y, w, h, color, fill)

    // --- Send it once ---
    oled.show()                                                                                                 // send framebuffer, () → void
    Thread.sleep(3000)

    // --- Animate with hardware drawing ---
    // The chip draws the marker straight into its RAM: no framebuffer transfer per step.
    for (step in 0 until 60) {
        val x = 4 + (step * 3) % 80
        oled.show()                                                                                             // send framebuffer, () → void
        oled.hwRect(x, 36, 10, 10, Ssd1331Full.WHITE, Ssd1331Full.RED, true)                                    // hardware rectangle, (x, y, w, h, lineColor, fillColor, fill) → void
        Thread.sleep(50)
    }

    // --- Scroll the test card ---
    // The chip scrolls its own RAM, one column every 6 frames.
    oled.show()                                                                                                 // send framebuffer, () → void
    oled.scroll(1, 0, 64, 0, 0)                                                                                 // scroll, (horizontal, startRow, rows, vertical, interval 0..3) → void
    connection.close()
}
