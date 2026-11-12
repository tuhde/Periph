///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0
// SSD1305 demo example: 1.3" 128 x 64 OLED module (SSD1305, RAM columns 4..131) over I2C on a Raspberry Pi (JVM).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3 (OLED supply VCC 7..15 V from the module, no charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Demo: a bouncing ball inside a border, a contrast fade, inverse flashes, hardware scroll and sleep.

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.display.SSD1305Full

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x3C
    I2CConnection(bus, addr).use { conn ->                                    // open I2C connection, (bus, address=0x3C) → I2CConnection
        val lcd = SSD1305Full(conn)                                            // construct driver, (connection, width=128, height=64, columnOffset=4) → SSD1305Full

        // --- Draw the border once ---
        // A one-pixel border around the 128 x 64 panel; the ball bounces inside it.
        lcd.clear()                                                           // clear framebuffer, () → void
        lcd.drawRect(0, 0, 128, 64, true, false)                              // draw rectangle, (x, y, w, h, on, fill) → void
        lcd.show()                                                            // send framebuffer to the display, () → void

        // --- Bounce a 6 x 6 ball at about 20 frames per second ---
        // The ball is erased and redrawn in the framebuffer, then the frame is sent with show().
        var bx = 20
        var by = 12
        var dx = 3
        var dy = 2
        for (frame in 0 until 200) {
            lcd.drawRect(bx, by, 6, 6, false, true)                           // draw rectangle, (x, y, w, h, on, fill) → void
            bx += dx
            by += dy
            if (bx < 2 || bx > 120) { dx = -dx; bx += 2 * dx; }
            if (by < 2 || by > 56) { dy = -dy; by += 2 * dy; }
            lcd.drawRect(bx, by, 6, 6, true, true)                            // draw rectangle, (x, y, w, h, on, fill) → void
            lcd.show()                                                        // send framebuffer to the display, () → void
            Thread.sleep(50)
        }

        // --- Fade the contrast down and back up ---
        // 255 -> 0 -> 255 in steps of 5 shows the full range of the segment drive current.
        for (v in 255 downTo 0 step 5) {
            lcd.setContrast(v)                                                // set contrast, (value 0..255) → void
            Thread.sleep(20)
        }
        for (v in 0..255 step 5) {
            lcd.setContrast(v)                                                // set contrast, (value 0..255) → void
            Thread.sleep(20)
        }

        // --- Flash the inverse display twice ---
        for (i in 0 until 2) {
            lcd.setInvert(true)                                               // invert display, (on) → void
            Thread.sleep(400)
            lcd.setInvert(false)                                              // invert display, (on) → void
            Thread.sleep(400)
        }

        // --- Dim mode ---
        // Dim mode lowers contrast and colour-bank brightness to the levels stored with setDimMode.
        lcd.setDimMode(0x10, 0x10)                                            // configure dim levels, (contrast 0..255, brightness 0..255) → void
        lcd.setDim(true)                                                      // dim mode, (on) → void
        Thread.sleep(2000)
        lcd.setDim(false)                                                     // dim mode, (on) → void

        // --- Hardware scroll ---
        // The scroll engine moves all eight pages right in the controller without re-sending any data;
        // stopping rewrites the RAM from the framebuffer as the datasheet requires.
        lcd.setScrollHorizontal(true, 0, 7, 0, 1)                             // set horizontal scroll, (right, startPage, endPage, interval 0..6, columns) → void
        lcd.scrollStart()                                                     // start scrolling, () → void
        Thread.sleep(5000)
        lcd.scrollStop()                                                      // stop scrolling, () → void

        // --- Power down ---
        // Sleep turns the display off (switch VCC off afterwards); RAM and registers are kept.
        lcd.sleep()                                                           // enter sleep, () → void
    }
}
