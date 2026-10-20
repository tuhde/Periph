///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1
// UC1611s complete example: EA DOGXL240-7 (240 x 128, 3.3 V only) over I2C on a Raspberry Pi (JVM).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VDD = 3V3; module pins BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39.
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 2-4.7 k to 3.3 V. Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, partial display, RAM screens, reads, sleep, reset.

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.display.Uc1611sFull

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x38"
int addr = Integer.decode(addrStr)

I2CConnection command = new I2CConnection(bus, addr)                            // open command connection, (bus, address=0x38) → I2CConnection
I2CConnection data = new I2CConnection(bus, addr + 1)                           // open data connection, (bus, address=0x39) → I2CConnection
try {
    def lcd = new Uc1611sFull(command, data)                                  // construct driver, (command, data, width=240, height=128) → Uc1611sFull

    // Framebuffer drawing: everything is composed in RAM and sent with show().
    lcd.clear()                                                               // clear framebuffer, () → void
                                                                              // zeroes every pixel of the framebuffer; the panel is unchanged until show()
    lcd.fill(true)                                                            // fill framebuffer, (on) → void
                                                                              // sets (or clears) every pixel
    lcd.fill(false)                                                           // fill framebuffer, (on) → void
    lcd.drawRect(0, 0, 240, 128, true, false)                                 // draw rectangle, (x, y, w, h, on, fill) → void
                                                                              // one-pixel outline around the whole panel
    lcd.drawLine(0, 0, 239, 127, true)                                        // draw line, (x0, y0, x1, y1, on) → void
                                                                              // Bresenham line into the framebuffer
    lcd.drawLine(0, 127, 239, 0, true)                                        // draw line, (x0, y0, x1, y1, on) → void
    lcd.drawRect(20, 20, 40, 30, true, true)                                  // draw rectangle, (x, y, w, h, on, fill) → void
                                                                              // filled rectangle
    lcd.drawRect(30, 28, 20, 14, false, true)                                 // draw rectangle, (x, y, w, h, on, fill) → void
                                                                              // on=false clears pixels: a hole in the filled rectangle
    lcd.setPixel(120, 64, true)                                               // set pixel, (x, y, on) → void
    boolean lit = lcd.getPixel(120, 64)                                       // read framebuffer pixel, (x, y) → boolean
                                                                              // reads the framebuffer, not the panel
    byte[] icon = [0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00] as byte[]
    lcd.blit(180, 16, icon, 2)                                                // copy bitmap, (x, y multiple of 8, columns, heightPages) → void
                                                                              // two page rows of 8 columns, bit 0 = top pixel
    lcd.show()                                                                // send framebuffer to the display, () → void
                                                                              // per page: page + column address, then 240 data bytes in 32-byte chunks
    Thread.sleep(2000)
    lcd.showPage(2)                                                           // send one framebuffer page, (page 0..15) → void
                                                                              // cheaper partial update of 8 rows

    // Contrast and display modes.
    lcd.setContrast(0x70)                                                     // set contrast, (value 0..255) → void
                                                                              // writes the potentiometer PM; lower = lighter
    Thread.sleep(800)
    lcd.setContrast(0x8F)                                                     // set contrast, (value 0..255) → void
                                                                              // 0x8F is the DOG example value
    lcd.setInvert(true)                                                       // invert display, (on) → void
                                                                              // bit-wise inversion of what is displayed; RAM unchanged
    Thread.sleep(800)
    lcd.setInvert(false)                                                      // invert display, (on) → void
    lcd.setAllOn(true)                                                        // light all pixels, (on) → void
                                                                              // test mode, RAM unchanged
    Thread.sleep(800)
    lcd.setAllOn(false)                                                       // light all pixels, (on) → void
    lcd.setFlip(false, true)                                                  // mirror display, (mirrorX, mirrorY) → void
                                                                              // MY = 1: rows mirrored at once; MX is applied to RAM writes
    Thread.sleep(800)
    lcd.setFlip(true, false)                                                  // mirror display, (mirrorX, mirrorY) → void
                                                                              // back to the bottom-view default
    lcd.show()                                                                // send framebuffer to the display, () → void

    // Hardware scroll with a fixed header.
    lcd.setFixedLines(2)                                                      // set fixed lines, (lines 0..15) → void
                                                                              // the top 2 x 2 = 4 rows stay put
    for (int line = 0; line <= 32; line += 4) {
        lcd.setScrollLine(line)                                               // scroll the image up, (line 0..159 - 2 * fixed) → void
                                                                              // rows enter from the bottom as the image moves up
        Thread.sleep(120)
    }
    lcd.setScrollLine(0)                                                      // scroll the image up, (line 0..159 - 2 * fixed) → void
    lcd.setFixedLines(0)                                                      // set fixed lines, (lines 0..15) → void

    // LCD drive tuning (the DOG defaults are already sent at init).
    lcd.setBiasRatio(2)                                                       // set bias ratio, (ratio 0..3) → void
                                                                              // 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12
    lcd.setTemperatureCompensation(1)                                         // set temperature compensation, (curve 0..3) → void
                                                                              // 1 = -0.10 %/degC, the DOG example value
    lcd.setLineRate(3)                                                        // set line rate, (rate 0..3) → void
                                                                              // 3 = 9.4 kilo-lines per second in On/Off mode
    lcd.setPump(true)                                                         // select VLCD source, (internal) → void
                                                                              // internal 11x charge pump
    lcd.setPanelLoading(true)                                                 // set panel loading, (high) → void
                                                                              // 33..55 nF panel capacitance
    lcd.setNLineInversion(0, false)                                           // set N-line inversion, (lines 0..63, xor) → void
                                                                              // 0 = off
    lcd.setIsolationClock(1, 0)                                               // set isolation clock, (front 0..15, back 0..15) → void
                                                                              // the reset values

    // Partial display: scan only the middle 64 rows.
    lcd.setPartialDisplay(true, 32, 95)                                       // set partial display, (on, start, end) → void
                                                                              // rows outside 32..95 are not scanned and save power
    Thread.sleep(1500)
    lcd.setPartialDisplay(false, 0, -1)                                       // set partial display, (on, start, end) → void
                                                                              // back to the full panel

    // Four RAM screens: draw into screen 1, then switch to it.
    lcd.setWritePattern(1)                                                    // select RAM screen to write, (pattern 0..3) → void
                                                                              // show() now writes RAM screen 1
    lcd.fill(false)                                                           // fill framebuffer, (on) → void
    lcd.drawRect(40, 30, 160, 68, true, true)                                 // draw rectangle, (x, y, w, h, on, fill) → void
    lcd.show()                                                                // send framebuffer to the display, () → void
                                                                              // screen 0 is still displayed
    lcd.setDisplayPattern(1)                                                  // select displayed RAM screen, (pattern 0..3) → void
                                                                              // the display now shows RAM screen 1
    Thread.sleep(1500)
    lcd.setDisplayPattern(0)                                                  // select displayed RAM screen, (pattern 0..3) → void
    lcd.setWritePattern(0)                                                    // select RAM screen to write, (pattern 0..3) → void

    // Read back over I2C.
    byte[] status = lcd.readStatus()                                          // read status bytes, () → byte[3]
                                                                              // NOP first, then Status1 = Ver MX MY WA DE WS MD MS, Status3 high nibble = product code 1
    println(String.format("status %02X %02X %02X", status[0], status[1], status[2]))
    byte[] ram = lcd.readRam(0, 0, 8)                                         // read display RAM, (page, column, n) → byte[n]
                                                                              // the dummy byte after the address change is discarded
    println("ram " + ram.collect { String.format("%02x", it) }.join(""))

    // Sleep, raw access, reset.
    lcd.sleep()                                                               // enter sleep, () → void
                                                                              // drivers, pump and clock stop; registers and RAM are kept
    Thread.sleep(1000)
    lcd.wake()                                                                // leave sleep, () → void
                                                                              // polls Status1.DE while the chip restores VLCD
    lcd.sendCommand(0xE3)                                                     // send raw command bytes, (bytes...) → void
                                                                              // 0xE3 = NOP
    lcd.sendData(new byte[8])                                                 // send raw RAM data, (bytes) → void
                                                                              // written at the current RAM address
    lcd.reset()                                                               // System Reset and reconfigure, () → void
                                                                              // registers back to the DOG settings with the cached contrast and mapping; RAM is kept
    println("done (I2C retries ${lcd.retryCount()}, unrecovered ${lcd.errorCount()})" + (lit ? "" : ""))
} finally {
    data.close()
    command.close()
}
