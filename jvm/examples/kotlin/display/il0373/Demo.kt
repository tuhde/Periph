///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.DisplayConnection
import it.uhde.periph.connection.PollingInputPin
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.connection.WireMode
import it.uhde.periph.chips.display.Il0373Full
import it.uhde.periph.chips.display.Il0373Minimal

// IL0373 demo example: Waveshare 2.13inch e-Paper (D) on a Raspberry Pi.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// Draws a status card and prints the refresh time (the number the spec's Timing Constraints
// are missing).

fun main() {
    val dc = SysfsOutputPin(25)                                                                  // create D/C# pin, (gpio=25) → OutputPin
    val res = SysfsOutputPin(17)                                                                 // create RES# pin, (gpio=17) → OutputPin
    val busy = PollingInputPin("/dev/gpiochip0", 24)                                             // create BUSY pin, (chip, line=24) → InputPin
    val connection = DisplayConnection.hardware(0, 0, 4000000, dc, res, busy, WireMode.SIMPLEX)  // open display connection, (bus, device, speedHz, dc, res, busy, wireMode)
    val epd = Il0373Full(connection)                                                            // construct driver, (connection) → Il0373Full

    // --- Compose the status card in the framebuffer ---
    // White page, a one-pixel black border inset by 2 px (a frame on the very edge is
    // hidden by the panel bezel) and a black header bar across the top 24 rows.
    epd.clear(Il0373Minimal.WHITE)                                                                 // fill framebuffer, (color=WHITE) → void
    epd.drawRect(2, 2, 100, 208, Il0373Minimal.BLACK, false)                                       // draw rectangle, (x, y, w, h, color, fill) → void
    epd.drawRect(3, 3, 98, 24, Il0373Minimal.BLACK, true)                                           // draw rectangle, (x, y, w, h, color, fill) → void

    // --- Bar chart of eight readings ---
    // Axes first, then one filled bar per value; the tallest bar is filled and the others are outlines.
    epd.drawLine(10, 190, 95, 190, Il0373Minimal.BLACK)                                           // draw line, (x0, y0, x1, y1, color) → void
    epd.drawLine(10, 60, 10, 190, Il0373Minimal.BLACK)                                             // draw line, (x0, y0, x1, y1, color) → void
    val values = intArrayOf(30, 55, 80, 45, 120, 90, 60, 100)
    val peak = values.max()
    for (i in values.indices) {
        epd.drawRect(14 + i * 10, 190 - values[i], 8, values[i], Il0373Minimal.BLACK, values[i] == peak) // draw rectangle, (x, y, w, h, color, fill) → void
    }

    // --- Refresh and time it ---
    // show() blocks for the whole DTM1/DTM2/DRF/POF sequence.
    val start = System.nanoTime()
    epd.show()                                                                                   // refresh panel, () → void
    println("refresh took ${(System.nanoTime() - start) / 1e9} s")

    // --- Save the image and power down ---
    // The panel keeps the picture without power; deep sleep stops the controller drawing current.
    epd.sleep()                                                                                  // enter deep sleep, () → void
    connection.close()
}
