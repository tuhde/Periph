///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.DisplayConnection
import it.uhde.periph.connection.PollingInputPin
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.connection.WireMode
import it.uhde.periph.chips.display.Uc8276cFull
import it.uhde.periph.chips.display.Uc8276cMinimal

// UC8276C demo example: Waveshare 4.2inch e-Paper (B) on a Raspberry Pi.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// Draws a status card and prints the refresh time (the number the spec's Timing Constraints
// are missing).

fun main() {
    val dc = SysfsOutputPin(25)                                                                  // create D/C# pin, (gpio=25) → OutputPin
    val res = SysfsOutputPin(17)                                                                 // create RES# pin, (gpio=17) → OutputPin
    val busy = PollingInputPin("/dev/gpiochip0", 24)                                             // create BUSY_N pin, (chip, line=24) → InputPin
    val connection = DisplayConnection.hardware(0, 0, 4000000, dc, res, busy, WireMode.SIMPLEX)  // open display connection, (bus, device, speedHz, dc, res, busy, wireMode)
    val epd = Uc8276cFull(connection)                                                            // construct driver, (connection) → Uc8276cFull

    // --- Compose the status card in the framebuffer ---
    // White page, a one-pixel black border inset by 4 px (a frame on the very edge is
    // hidden by the panel bezel) and a red header bar across the top 40 rows.
    epd.clear(Uc8276cMinimal.WHITE)                                                                 // fill framebuffer, (color=WHITE) → void
    epd.drawRect(4, 4, 392, 292, Uc8276cMinimal.BLACK, false)                                       // draw rectangle, (x, y, w, h, color, fill) → void
    epd.drawRect(5, 5, 390, 40, Uc8276cMinimal.RED, true)                                           // draw rectangle, (x, y, w, h, color, fill) → void

    // --- Bar chart of eight readings ---
    // Axes first, then one filled bar per value; the tallest bar is red to flag the peak.
    epd.drawLine(30, 270, 370, 270, Uc8276cMinimal.BLACK)                                           // draw line, (x0, y0, x1, y1, color) → void
    epd.drawLine(30, 70, 30, 270, Uc8276cMinimal.BLACK)                                             // draw line, (x0, y0, x1, y1, color) → void
    val values = intArrayOf(30, 55, 80, 45, 120, 90, 60, 100)
    val peak = values.max()
    for (i in values.indices) {
        epd.drawRect(45 + i * 40, 270 - values[i], 28, values[i], if (values[i] == peak) Uc8276cMinimal.RED else Uc8276cMinimal.BLACK, true) // draw rectangle, (x, y, w, h, color, fill) → void
    }

    // --- Refresh and time it ---
    // show() blocks for the whole DTM1/DTM2/PON/DRF/POF sequence.
    val start = System.nanoTime()
    epd.show()                                                                                   // refresh panel, () → void
    println("refresh took ${(System.nanoTime() - start) / 1e9} s")

    // --- Save the image and power down ---
    // The panel keeps the picture without power; deep sleep stops the controller drawing current.
    epd.sleep()                                                                                  // enter deep sleep, () → void
    connection.close()
}
