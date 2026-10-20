///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1
// UC1611s demo example (4-wire SPI): EA DOGXL240-7 (240 x 128, 3.3 V only) on a Raspberry Pi (JVM).
// Wiring: SI=GPIO10 (SPI0 MOSI), SCK=GPIO11, CS0=GPIO8 (CE0), CD=GPIO25, RST=GPIO17; VDD=3V3; module pins BM0 = D13 = VSS (4-wire SPI).
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD. Demo: a bouncing ball with partial page updates, a contrast sweep, inverse flashes, hardware scroll, a second RAM screen and sleep.

import it.uhde.periph.connection.DisplayConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.connection.WireMode
import it.uhde.periph.chips.display.Uc1611sSpiFull

fun main() {
    val dc = SysfsOutputPin(25)                                                                             // create CD (D/C) pin, (gpio=25) → OutputPin
    val res = SysfsOutputPin(17)                                                                            // create RST pin, (gpio=17) → OutputPin
    DisplayConnection.hardware(0, 0, 4000000, dc, res, null, WireMode.SIMPLEX).use { connection ->          // open display connection, (bus, device, speedHz=4 MHz, dc, res, busy=null, wireMode)
    val lcd = Uc1611sSpiFull(connection)  // construct driver, (connection, width=240, height=128) → Uc1611sSpiFull

        // --- Draw the frame once ---
        // A one-pixel border around the whole 240 x 128 panel; the ball bounces inside it.
        lcd.clear()                                                       // clear framebuffer, () → void
        lcd.drawRect(0, 0, 240, 128, true, false)                         // draw rectangle, (x, y, w, h, on, fill) → void
        lcd.show()                                                        // send framebuffer to the display, () → void

        // --- Bounce an 8 x 8 ball with partial page updates ---
        // Only the page rows the ball touches are re-sent, so the animation runs at about
        // 20 frames per second, instead of the 8 of a full-frame show().
        var bx = 20; var by = 12; var dx = 3; var dy = 2
        for (frame in 0 until 200) {
            val oldy = by
            lcd.drawRect(bx, by, 8, 8, false, true)                       // draw rectangle, (x, y, w, h, on, fill) → void
            bx += dx
            by += dy
            if (bx < 2 || bx > 229) { dx = -dx; bx += 2 * dx; }
            if (by < 2 || by > 118) { dy = -dy; by += 2 * dy; }
            lcd.drawRect(bx, by, 8, 8, true, true)                        // draw rectangle, (x, y, w, h, on, fill) → void
            for (page in minOf(oldy, by) / 8..(maxOf(oldy, by) + 7) / 8) {
                lcd.showPage(page)                                        // send one framebuffer page, (page 0..15) → void
            }
            Thread.sleep(20)
        }

        // --- Contrast sweep around the working point ---
        // The contrast is set once in normal use; sweeping PM shows the usable range of the panel.
        for (pm in 0x8F downTo 0x70 step 2) {
            lcd.setContrast(pm)                                           // set contrast, (value 0..255) → void
            Thread.sleep(40)
        }
        for (pm in 0x70..0x8F step 2) {
            lcd.setContrast(pm)                                           // set contrast, (value 0..255) → void
            Thread.sleep(40)
        }

        // --- Flash the inverse display twice ---
        repeat(2) {
            lcd.setInvert(true)                                           // invert display, (on) → void
            Thread.sleep(400)
            lcd.setInvert(false)                                          // invert display, (on) → void
            Thread.sleep(400)
        }

        // --- Hardware scroll ---
        // The image moves up in the controller, no data is re-sent; a blank band enters from the bottom
        // because RAM rows 128..159 are empty.
        for (line in 0..32) {
            lcd.setScrollLine(line)                                       // scroll the image up, (line 0..159 - 2 * fixed) → void
            Thread.sleep(40)
        }
        for (line in 32 downTo 0) {
            lcd.setScrollLine(line)                                       // scroll the image up, (line 0..159 - 2 * fixed) → void
            Thread.sleep(40)
        }

        // --- Second screen in RAM ---
        // The chip holds four black/white screens; draw a cross into screen 1 and switch to it.
        lcd.setWritePattern(1)                                            // select RAM screen to write, (pattern 0..3) → void
        lcd.fill(false)                                                   // fill framebuffer, (on) → void
        lcd.drawLine(0, 0, 239, 127, true)                                // draw line, (x0, y0, x1, y1, on) → void
        lcd.drawLine(0, 127, 239, 0, true)                                // draw line, (x0, y0, x1, y1, on) → void
        lcd.show()                                                        // send framebuffer to the display, () → void
        lcd.setDisplayPattern(1)                                          // select displayed RAM screen, (pattern 0..3) → void
        Thread.sleep(2000)
        lcd.setDisplayPattern(0)                                          // select displayed RAM screen, (pattern 0..3) → void
        lcd.setWritePattern(0)                                            // select RAM screen to write, (pattern 0..3) → void
        Thread.sleep(1500)

        // --- Power down ---
        // Sleep stops the charge pump and the drivers (below 1 uA); RAM and registers are kept.
        lcd.sleep()                                                       // enter sleep, () → void
        println("done")
    }
}
