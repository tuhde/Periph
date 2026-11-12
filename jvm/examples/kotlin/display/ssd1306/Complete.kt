///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0
// SSD1306 complete example: 0.96" 128 x 64 OLED module (SSD1306) over I2C on a Raspberry Pi (JVM).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3 (the module generates the OLED supply with the SSD1306 charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, reads, sleep.

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.display.SSD1306Full
import it.uhde.periph.chips.display.SSD1306Minimal

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x3C
    I2CConnection(bus, addr).use { conn ->                                    // open I2C connection, (bus, address=0x3C) → I2CConnection
        val lcd = SSD1306Full(conn)                                            // construct driver, (connection, width=128, height=64, externalVcc=false) → SSD1306Full

        // Framebuffer drawing: everything is composed in RAM and sent with show().
        lcd.clear()                                                           // clear framebuffer, () → void
                                                                              // zeroes every pixel of the framebuffer; the panel is unchanged until show()
        lcd.fill(true)                                                        // fill framebuffer, (on) → void
                                                                              // sets (or clears) every pixel
        lcd.fill(false)                                                       // fill framebuffer, (on) → void
        lcd.drawRect(0, 0, 128, 64, true, false)                              // draw rectangle, (x, y, w, h, on, fill) → void
                                                                              // one-pixel outline around the whole panel
        lcd.drawLine(0, 0, 127, 63, true)                                     // draw line, (x0, y0, x1, y1, on) → void
                                                                              // Bresenham line into the framebuffer
        lcd.drawLine(0, 63, 127, 0, true)                                     // draw line, (x0, y0, x1, y1, on) → void
        lcd.drawRect(10, 10, 30, 20, true, true)                              // draw rectangle, (x, y, w, h, on, fill) → void
                                                                              // filled rectangle
        lcd.drawRect(18, 16, 14, 8, false, true)                              // draw rectangle, (x, y, w, h, on, fill) → void
                                                                              // on=false clears pixels: a hole in the filled rectangle
        lcd.setPixel(64, 32, true)                                            // set pixel, (x, y, on) → void
        val lit = lcd.getPixel(64, 32)                                        // read framebuffer pixel, (x, y) → boolean
                                                                              // reads the framebuffer, not the panel
        val icon = intArrayOf(0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00).map { it.toByte() }.toByteArray()
        lcd.blit(100, 16, icon, 2)                                            // copy bitmap, (x, y multiple of 8, columns, heightPages) → void
                                                                              // two page rows of 8 columns, bit 0 = top pixel
        lcd.show()                                                            // send framebuffer to the display, () → void
                                                                              // per page: page + column command, then 128 data bytes in 31-byte chunks
        Thread.sleep(2000)
        lcd.showPage(2)                                                       // send one framebuffer page, (page 0..7) → void
                                                                              // cheaper partial update of 8 rows

        // Contrast and display modes.
        lcd.setContrast(0x20)                                                 // set contrast, (value 0..255) → void
                                                                              // segment drive current; reset value is 0x7F
        Thread.sleep(800)
        lcd.setContrast(0x7F)                                                 // set contrast, (value 0..255) → void
        lcd.setInvert(true)                                                   // invert display, (on) → void
                                                                              // RAM 0 = pixel on; RAM is unchanged
        Thread.sleep(800)
        lcd.setInvert(false)                                                  // invert display, (on) → void
        lcd.setAllOn(true)                                                    // force all pixels on, (on) → void
                                                                              // test mode, RAM is kept
        Thread.sleep(800)
        lcd.setAllOn(false)                                                   // force all pixels on, (on) → void
        lcd.setFlip(true, true)                                               // mirror display, (horizontal, vertical) → void
                                                                              // segment re-map and COM scan direction; framebuffer unchanged
        Thread.sleep(1500)
        lcd.setFlip(false, false)                                             // mirror display, (horizontal, vertical) → void

        // Hardware vertical scroll and drive tuning.
        for (line in 0 until 64 step 2) {
            lcd.setStartLine(line)                                            // scroll the image up, (line 0..63) → void
                                                                              // RAM line shown at COM0; the image wraps around
            Thread.sleep(30)
        }
        lcd.setStartLine(0)                                                   // scroll the image up, (line 0..63) → void
        lcd.setDisplayOffset(0)                                               // set COM offset, (offset 0..63) → void
                                                                              // shifts the COM mapping
        lcd.setMultiplex(64)                                                  // set multiplex ratio, (ratio 16..64) → void
                                                                              // number of driven rows
        lcd.setClock(1, 8)                                                    // set clock, (divide 1..16, osc 0..15) → void
                                                                              // divide ratio 1 at the reset oscillator setting
        lcd.setPrecharge(2, 2)                                                // set pre-charge, (phase1 1..15, phase2 1..15) → void
                                                                              // pre-charge and discharge periods in display clocks
        lcd.setComConfig(true, false)                                                // set COM pin configuration, (alternative, remap) → void
                                                                              // alternative is the reset value and right for 128 x 64 modules
        lcd.setVcomh(0x20)                                                    // set VCOMH deselect level, (value 0x00..0x70) → void
                                                                              // 0x20 (about 0.77 x VCC) is the reset value
        lcd.setChargePump(true)                                               // enable the charge pump, (enabled) → void
                                                                              // 0x8D 0x14; takes effect with the display on

        // Addressing modes.
        lcd.setAddressingMode(SSD1306Minimal.ADDR_HORIZONTAL)                 // select addressing mode, (mode ADDR_PAGE|ADDR_HORIZONTAL|ADDR_VERTICAL) → void
                                                                              // show() then streams the frame in one transfer
        lcd.show()                                                            // update display, () → void
        lcd.setAddressingMode(SSD1306Minimal.ADDR_PAGE)                       // select addressing mode, (mode ADDR_PAGE|ADDR_HORIZONTAL|ADDR_VERTICAL) → void

        // Hardware scrolling (RAM access is refused while a scroll is active).
        lcd.setScrollHorizontal(true, 0, 7, 7)                                // set horizontal scroll, (right, startPage, endPage, interval 0..7) → void
                                                                              // interval index 7 = 2 frames per step
        lcd.scrollStart()                                                     // start scrolling, () → void
        Thread.sleep(2000)
        lcd.scrollStop()                                                      // stop scrolling, () → void
                                                                              // deactivates and rewrites the RAM from the framebuffer
        lcd.setScrollArea(0, 64)                                              // set vertical scroll area, (topFixed 0..63, scrollRows 1..64) → void
        lcd.setScrollVertical(true, 0, 7, 7, 1)                               // set diagonal scroll, (right, startPage, endPage, interval 0..7, rows 1..63) → void
        lcd.scrollStart()                                                     // start scrolling, () → void
        Thread.sleep(2000)
        lcd.scrollStop()                                                      // stop scrolling, () → void

        // Raw commands.
        lcd.sendCommand(0xE3)                                                 // send raw command stream, (bytes...) → void
                                                                              // NOP

        // Power.
        lcd.setDisplay(false)                                                 // display on/off, (on) → void
                                                                              // RAM and settings are kept
        Thread.sleep(1000)
        lcd.setDisplay(true)                                                  // display on/off, (on) → void
        lcd.sleep()                                                           // enter sleep, () → void
                                                                              // display off; switch VCC off afterwards
        Thread.sleep(1000)
        lcd.wake()                                                            // leave sleep, () → void
                                                                              // display on and a 100 ms wait (tAF)
    }
}
