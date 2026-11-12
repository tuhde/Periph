///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0
// SSD1305 complete example: 1.3" 128 x 64 OLED module (SSD1305, RAM columns 4..131) over I2C on a Raspberry Pi (JVM).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3 (OLED supply VCC 7..15 V from the module, no charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, reads, sleep.

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.display.SSD1305Full

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x3C
    I2CConnection(bus, addr).use { conn ->                                    // open I2C connection, (bus, address=0x3C) → I2CConnection
        val lcd = SSD1305Full(conn)                                            // construct driver, (connection, width=128, height=64, columnOffset=4) → SSD1305Full

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
                                                                              // segment drive current; reset value is 0x80
        Thread.sleep(800)
        lcd.setContrast(0x80)                                                 // set contrast, (value 0..255) → void
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
        lcd.setClock(1, 7)                                                    // set clock, (divide 1..16, osc 0..15) → void
                                                                              // divide ratio 1 at the reset oscillator setting
        lcd.setPrecharge(2, 2)                                                // set pre-charge, (phase1 1..15, phase2 1..15) → void
                                                                              // pre-charge and discharge periods in display clocks
        lcd.setComConfig(true, false)                                                // set COM pin configuration, (alternative, remap) → void
                                                                              // alternative is the reset value and right for 128 x 64 modules
        lcd.setVcomh(0x34)                                                    // set VCOMH deselect level, (value 0x00..0x3C) → void
                                                                              // 0x34 (about 0.77 x VCC) is the reset value
        lcd.setDimMode(0x40, 0x40)                                            // configure dim levels, (contrast 0..255, brightness 0..255) → void
                                                                              // used while dim mode is on
        lcd.setDim(true)                                                      // dim mode, (on) → void
                                                                              // 0xAC display on in dim mode
        Thread.sleep(1000)
        lcd.setDim(false)                                                     // dim mode, (on) → void
        lcd.setBrightness(0x80)                                               // set colour-bank brightness, (value 0..255) → void
                                                                              // no visible effect in monochrome mode
        lcd.setAreaColor(false, false)                                        // set area-colour mode, (enable, lowPower) → void
                                                                              // keep disabled on monochrome panels
        lcd.setLut(49, 63, 63, 63)                                            // set pulse widths, (bank0, a, b, c each 31..63) → void
                                                                              // reset values
        lcd.setBankColors(ByteArray(4), ByteArray(4))                         // assign bank colours, (banks1to16, banks17to32 or null) → void
                                                                              // all banks colour A
        lcd.setAddressingMode(SSD1305Minimal.ADDR_HORIZONTAL)                 // select addressing mode, (mode ADDR_PAGE|ADDR_HORIZONTAL|ADDR_VERTICAL) → void
                                                                              // show() then streams the frame in one transfer
        lcd.show()                                                            // update display, () → void
        lcd.setAddressingMode(SSD1305Minimal.ADDR_PAGE)                       // select addressing mode, (mode ADDR_PAGE|ADDR_HORIZONTAL|ADDR_VERTICAL) → void

        // Hardware scrolling.
        lcd.setScrollHorizontal(true, 0, 7, 0, 1)                             // set horizontal scroll, (right, startPage, endPage, interval 0..6, columns) → void
                                                                              // interval index 0 = 6 frames per step
        lcd.scrollStart()                                                     // start scrolling, () → void
        Thread.sleep(2000)
        lcd.scrollStop()                                                      // stop scrolling, () → void
                                                                              // deactivates and rewrites the RAM from the framebuffer
        lcd.setScrollArea(0, 64)                                              // set vertical scroll area, (topFixed 0..63, scrollRows 1..64) → void
        lcd.setScrollVertical(true, 0, 7, 0, 1, 1)                            // set vertical+horizontal scroll, (right, startPage, endPage, interval, columns, rows 1..63) → void
        lcd.scrollStart()                                                     // start scrolling, () → void
        Thread.sleep(2000)
        lcd.scrollStop()                                                      // stop scrolling, () → void

        // Reads (I2C only) and raw commands.
        var status = lcd.readStatus()                                         // read status, () → int
                                                                              // bit 6 = 1 when the display is off
        val ram = lcd.readPage(0, 2, 8)                                       // read display RAM, (page 0..7, column, n) → byte[]
                                                                              // sets the address and discards the dummy byte
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
        println("status 0x" + Integer.toHexString(status) + ", lit " + lit + ", ram bytes " + ram.size)
    }
}
