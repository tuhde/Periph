///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.DisplayConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.connection.WireMode
import it.uhde.periph.chips.display.Ssd1331Full

// SSD1331 complete example: Waveshare 0.95inch RGB OLED on a Raspberry Pi.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17 (no BUSY line).
// Complete: every Full method — software drawing, partial update, hardware line/rectangle/copy/dim/clear, scrolling, contrast, master current, flip, display modes, power save.

fun main() {
    val dc = SysfsOutputPin(25)                                                                                 // create D/C# pin, (gpio=25) → OutputPin
    val res = SysfsOutputPin(17)                                                                                // create RES# pin, (gpio=17) → OutputPin
    val connection = DisplayConnection.hardware(0, 0, 4000000, dc, res, null, WireMode.SIMPLEX)                 // open display connection, (bus, device, speedHz, dc, res, busy=null, wireMode)
    val oled = Ssd1331Full(connection)                                                                          // construct driver, (connection) → Ssd1331Full
    oled.clear(Ssd1331Full.BLACK)                                                                               // fill framebuffer, (color) — the display is unchanged until show()
    oled.drawRect(0, 0, 96, 64, Ssd1331Full.WHITE, false)                                                       // draw rectangle, (x, y, w, h, color, fill)
    oled.drawRect(4, 4, 30, 20, Ssd1331Full.RED, true)                                                          // draw rectangle, (x, y, w, h, color, fill)
    oled.drawLine(0, 0, 95, 63, Ssd1331Full.GREEN)                                                              // software line, (x0, y0, x1, y1, color) — Bresenham diagonal
    oled.drawLine(0, 63, 95, 0, Ssd1331Full.CYAN)                                                               // software line, (x0, y0, x1, y1, color)
    val sprite = IntArray(64) { i -> if ((((i / 8) + (i % 8)) and 1) == 1) Ssd1331Full.YELLOW else Ssd1331Full.MAGENTA }
    oled.blit(60, 8, sprite, 8, 8)                                                                              // copy RGB565 bitmap, (x, y, pixels, w, h)
    oled.setPixel(48, 32, Ssd1331Full.WHITE)                                                                    // set one pixel, (x, y, color)
    println(oled.getPixel(48, 32))                                                                              // read framebuffer pixel, (x, y) → int RGB565
    oled.show()                                                                                                 // send framebuffer, () → void — window 0..95 x 0..63 then 12288 bytes
    Thread.sleep(2000)
    oled.clear(Ssd1331Full.BLUE)                                                                                // fill framebuffer, (color)
    oled.drawRect(20, 16, 56, 32, Ssd1331Full.WHITE, true)                                                      // draw rectangle, (x, y, w, h, color, fill)
    oled.showRegion(16, 12, 64, 40)                                                                             // send region, (x, y, w, h) → void — only the rows of the window are transferred
    Thread.sleep(2000)
    oled.hwClear(0, 0, 95, 63)                                                                                  // clear window in display RAM, (x0, y0, x1, y1) → void — the framebuffer is untouched
    oled.hwRect(10, 10, 76, 44, Ssd1331Full.YELLOW, Ssd1331Full.RED, true)                                      // hardware rectangle, (x, y, w, h, lineColor, fillColor, fill) → void
    oled.hwLine(0, 0, 95, 63, Ssd1331Full.WHITE)                                                                // hardware line, (x0, y0, x1, y1, color) → void
    Thread.sleep(2000)
    oled.hwCopy(10, 10, 40, 40, 50, 20)                                                                         // hardware copy, (x0, y0, x1, y1, x, y) → void
    Thread.sleep(2000)
    oled.hwDim(0, 0, 47, 63)                                                                                    // hardware dim window, (x0, y0, x1, y1) → void
    Thread.sleep(2000)
    oled.setDisplayMode(Ssd1331Full.MODE_INVERSE)                                                               // display mode, (mode) → void
    Thread.sleep(1500)
    oled.setDisplayMode(Ssd1331Full.MODE_ALL_ON)                                                                // display mode, (mode) → void
    Thread.sleep(1000)
    oled.setDisplayMode(Ssd1331Full.MODE_ALL_OFF)                                                               // display mode, (mode) → void
    Thread.sleep(1000)
    oled.setDisplayMode(Ssd1331Full.MODE_NORMAL)                                                                // display mode, (mode) → void
    oled.setContrast(0x30, 0x30, 0x30)                                                                          // channel contrast, (a, b, c) 0..255
    Thread.sleep(1000)
    oled.setContrast(0xFF, 0xFF, 0xFF)                                                                          // channel contrast, (a, b, c) 0..255
    oled.setMasterCurrent(4)                                                                                    // master current, (n 1..16) n/16
    Thread.sleep(1000)
    oled.setMasterCurrent(7)                                                                                    // master current, (n 1..16) n/16
    oled.setFlip(false, true)                                                                                   // mirror, (horizontal, vertical) → void
    oled.show()                                                                                                 // send framebuffer, () → void
    Thread.sleep(1500)
    oled.setFlip(true, true)                                                                                    // mirror, (horizontal, vertical) → void
    oled.show()                                                                                                 // send framebuffer, () → void
    Thread.sleep(1500)
    oled.scroll(1, 0, 64, 0, 0)                                                                                 // scroll, (horizontal, startRow, rows, vertical, interval 0..3) — one column per 6 frames
    Thread.sleep(4000)
    oled.scrollStop()                                                                                           // stop scrolling, () → void
    oled.dimMode(0x10, 0x10, 0x10, 0x10)                                                                        // dim mode, (a, b, c, precharge 0..31) → void
    Thread.sleep(1500)
    oled.setPowerSave(true)                                                                                     // power save, (on) → void
    oled.displayOff()                                                                                           // display off, () → void
    connection.close()
}
