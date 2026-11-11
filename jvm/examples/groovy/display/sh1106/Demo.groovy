///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0
// SH1106 demo example: 1.3" 128 x 64 OLED module (SH1106, RAM columns 2..129) over I2C on a Raspberry Pi (JVM).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3; address 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD1). Demo: a bouncing ball inside a border, a contrast fade, inverse flashes, hardware scroll and sleep.

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.display.SH1106Full

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x3C"
int addr = Integer.decode(addrStr)

I2CConnection conn = new I2CConnection(bus, addr)                             // open I2C connection, (bus, address=0x3C) → I2CConnection
try {
    def lcd = new SH1106Full(conn)                                            // construct driver, (connection, width=128, height=64, columnOffset=2, internalDcdc=true) → SH1106Full

    // --- Draw the border once ---
    // A one-pixel border around the 128 x 64 panel; the ball bounces inside it.
    lcd.clear()                                                               // clear framebuffer, () → void
    lcd.drawRect(0, 0, 128, 64, true, false)                                  // draw rectangle, (x, y, w, h, on, fill) → void
    lcd.show()                                                                // send framebuffer to the display, () → void

    // --- Bounce a 6 x 6 ball at about 20 frames per second ---
    // The ball is erased and redrawn in the framebuffer, then the frame is sent with show().
    int bx = 20; int by = 12; int dx = 3; int dy = 2
    for (int frame = 0; frame < 200; frame++) {
        lcd.drawRect(bx, by, 6, 6, false, true)                               // draw rectangle, (x, y, w, h, on, fill) → void
        bx += dx
        by += dy
        if (bx < 2 || bx > 120) { dx = -dx; bx += 2 * dx; }
        if (by < 2 || by > 56) { dy = -dy; by += 2 * dy; }
        lcd.drawRect(bx, by, 6, 6, true, true)                                // draw rectangle, (x, y, w, h, on, fill) → void
        lcd.show()                                                            // send framebuffer to the display, () → void
        Thread.sleep(50)
    }

    // --- Fade the contrast down and back up ---
    // 255 -> 0 -> 255 in steps of 5 shows the full range of the segment drive current.
    for (int v = 255; v >= 0; v -= 5) {
        lcd.setContrast(v)                                                    // set contrast, (value 0..255) → void
        Thread.sleep(20)
    }
    for (int v = 0; v <= 255; v += 5) {
        lcd.setContrast(v)                                                    // set contrast, (value 0..255) → void
        Thread.sleep(20)
    }

    // --- Flash the inverse display twice ---
    for (int i = 0; i < 2; i++) {
        lcd.setInvert(true)                                                   // invert display, (on) → void
        Thread.sleep(400)
        lcd.setInvert(false)                                                  // invert display, (on) → void
        Thread.sleep(400)
    }

    // --- Hardware scroll ---
    // Stepping the display start line moves the image up inside the controller, no data is re-sent
    // the picture wraps around after 64 lines.
    for (int line = 0; line < 64; line++) {
        lcd.setStartLine(line)                                                // scroll the image up, (line 0..63) → void
        Thread.sleep(30)
    }
    for (int line = 63; line >= 0; line--) {
        lcd.setStartLine(line)                                                // scroll the image up, (line 0..63) → void
        Thread.sleep(30)
    }

    // --- Power down ---
    // Sleep turns the display and the charge pump off; RAM and registers are kept.
    lcd.sleep()                                                               // enter sleep, () → void
} finally {
    conn.close()
}
