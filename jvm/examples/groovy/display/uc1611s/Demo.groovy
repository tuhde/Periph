///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1
// UC1611s demo example: EA DOGXL240-7 (240 x 128, 3.3 V only) over I2C on a Raspberry Pi (JVM).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VDD = 3V3; module pins BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39.
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 2-4.7 k to 3.3 V. Demo: a bouncing ball with partial page updates, a contrast sweep, inverse flashes, hardware scroll, a second RAM screen and sleep.

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.display.Uc1611sFull

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x38"
int addr = Integer.decode(addrStr)

I2CConnection command = new I2CConnection(bus, addr)                            // open command connection, (bus, address=0x38) → I2CConnection
I2CConnection data = new I2CConnection(bus, addr + 1)                           // open data connection, (bus, address=0x39) → I2CConnection
try {
    def lcd = new Uc1611sFull(command, data)                                  // construct driver, (command, data, width=240, height=128) → Uc1611sFull

    // --- Draw the frame once ---
    // A one-pixel border around the whole 240 x 128 panel; the ball bounces inside it.
    lcd.clear()                                                               // clear framebuffer, () → void
    lcd.drawRect(0, 0, 240, 128, true, false)                                 // draw rectangle, (x, y, w, h, on, fill) → void
    lcd.show()                                                                // send framebuffer to the display, () → void

    // --- Bounce an 8 x 8 ball with partial page updates ---
    // Only the page rows the ball touches are re-sent, so the animation runs at about
    // 20 frames per second even at 400 kHz, instead of the 8 of a full-frame show().
    int bx = 20, by = 12, dx = 3, dy = 2
    for (int frame = 0; frame < 200; frame++) {
        int oldy = by
        lcd.drawRect(bx, by, 8, 8, false, true)                               // draw rectangle, (x, y, w, h, on, fill) → void
        bx += dx
        by += dy
        if (bx < 2 || bx > 229) { dx = -dx; bx += 2 * dx; }
        if (by < 2 || by > 118) { dy = -dy; by += 2 * dy; }
        lcd.drawRect(bx, by, 8, 8, true, true)                                // draw rectangle, (x, y, w, h, on, fill) → void
        for (int page = Math.min(oldy, by) / 8; page <= (Math.max(oldy, by) + 7) / 8; page++) {
            lcd.showPage(page)                                                // send one framebuffer page, (page 0..15) → void
        }
        Thread.sleep(20)
    }

    // --- Contrast sweep around the working point ---
    // The contrast is set once in normal use; sweeping PM shows the usable range of the panel.
    for (int pm = 0x8F; pm >= 0x70; pm -= 2) {
        lcd.setContrast(pm)                                                   // set contrast, (value 0..255) → void
        Thread.sleep(40)
    }
    for (int pm = 0x70; pm <= 0x8F; pm += 2) {
        lcd.setContrast(pm)                                                   // set contrast, (value 0..255) → void
        Thread.sleep(40)
    }

    // --- Flash the inverse display twice ---
    for (int i = 0; i < 2; i++) {
        lcd.setInvert(true)                                                   // invert display, (on) → void
        Thread.sleep(400)
        lcd.setInvert(false)                                                  // invert display, (on) → void
        Thread.sleep(400)
    }

    // --- Hardware scroll ---
    // The image moves up in the controller, no data is re-sent; a blank band enters from the bottom
    // because RAM rows 128..159 are empty.
    for (int line = 0; line <= 32; line++) {
        lcd.setScrollLine(line)                                               // scroll the image up, (line 0..159 - 2 * fixed) → void
        Thread.sleep(40)
    }
    for (int line = 32; line >= 0; line--) {
        lcd.setScrollLine(line)                                               // scroll the image up, (line 0..159 - 2 * fixed) → void
        Thread.sleep(40)
    }

    // --- Second screen in RAM ---
    // The chip holds four black/white screens; draw a cross into screen 1 and switch to it.
    lcd.setWritePattern(1)                                                    // select RAM screen to write, (pattern 0..3) → void
    lcd.fill(false)                                                           // fill framebuffer, (on) → void
    lcd.drawLine(0, 0, 239, 127, true)                                        // draw line, (x0, y0, x1, y1, on) → void
    lcd.drawLine(0, 127, 239, 0, true)                                        // draw line, (x0, y0, x1, y1, on) → void
    lcd.show()                                                                // send framebuffer to the display, () → void
    lcd.setDisplayPattern(1)                                                  // select displayed RAM screen, (pattern 0..3) → void
    Thread.sleep(2000)
    lcd.setDisplayPattern(0)                                                  // select displayed RAM screen, (pattern 0..3) → void
    lcd.setWritePattern(0)                                                    // select RAM screen to write, (pattern 0..3) → void
    Thread.sleep(1500)

    // --- Power down ---
    // Sleep stops the charge pump and the drivers (below 1 uA); RAM and registers are kept.
    lcd.sleep()                                                               // enter sleep, () → void
    println("done (I2C retries ${lcd.retryCount()}, unrecovered ${lcd.errorCount()})")
} finally {
    data.close()
    command.close()
}
