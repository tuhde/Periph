///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0
// SSD1306 demo example: 0.96" 128 x 64 OLED module (SSD1306) over I2C on a Raspberry Pi (JVM).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3 (the module generates the OLED supply with the SSD1306 charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Demo: a bouncing ball inside a border, a contrast fade, inverse flashes, hardware scroll and sleep.

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.display.SSD1306Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x3C"));
        try (var conn = new I2CConnection(bus, addr)) {                       // open I2C connection, (bus, address=0x3C) → I2CConnection
            var lcd = new SSD1306Full(conn);                                   // construct driver, (connection, width=128, height=64, externalVcc=false) → SSD1306Full

            // --- Draw the border once ---
            // A one-pixel border around the 128 x 64 panel; the ball bounces inside it.
            lcd.clear();                                                      // clear framebuffer, () → void
            lcd.drawRect(0, 0, 128, 64, true, false);                         // draw rectangle, (x, y, w, h, on, fill) → void
            lcd.show();                                                       // send framebuffer to the display, () → void

            // --- Bounce a 6 x 6 ball at about 20 frames per second ---
            // The ball is erased and redrawn in the framebuffer, then the frame is sent with show().
            int bx = 20; int by = 12; int dx = 3; int dy = 2;
            for (int frame = 0; frame < 200; frame++) {
                lcd.drawRect(bx, by, 6, 6, false, true);                      // draw rectangle, (x, y, w, h, on, fill) → void
                bx += dx;
                by += dy;
                if (bx < 2 || bx > 120) { dx = -dx; bx += 2 * dx; }
                if (by < 2 || by > 56) { dy = -dy; by += 2 * dy; }
                lcd.drawRect(bx, by, 6, 6, true, true);                       // draw rectangle, (x, y, w, h, on, fill) → void
                lcd.show();                                                   // send framebuffer to the display, () → void
                Thread.sleep(50);
            }

            // --- Fade the contrast down and back up ---
            // 255 -> 0 -> 255 in steps of 5 shows the full range of the segment drive current.
            for (int v = 255; v >= 0; v -= 5) {
                lcd.setContrast(v);                                           // set contrast, (value 0..255) → void
                Thread.sleep(20);
            }
            for (int v = 0; v <= 255; v += 5) {
                lcd.setContrast(v);                                           // set contrast, (value 0..255) → void
                Thread.sleep(20);
            }

            // --- Flash the inverse display twice ---
            for (int i = 0; i < 2; i++) {
                lcd.setInvert(true);                                          // invert display, (on) → void
                Thread.sleep(400);
                lcd.setInvert(false);                                         // invert display, (on) → void
                Thread.sleep(400);
            }

            // --- Hardware scroll ---
            // The scroll engine moves all eight pages right in the controller without re-sending any data;
            // RAM access is prohibited while it runs, so it is stopped before the next show().
            lcd.setScrollHorizontal(true, 0, 7, 7);                           // set horizontal scroll, (right, startPage, endPage, interval 0..7) → void
            lcd.scrollStart();                                                // start scrolling, () → void
            Thread.sleep(5000);
            lcd.scrollStop();                                                 // stop scrolling, () → void

            // A diagonal scroll moves one row vertically per step in addition.
            lcd.setScrollVertical(true, 0, 7, 7, 1);                          // set diagonal scroll, (right, startPage, endPage, interval 0..7, rows 1..63) → void
            lcd.scrollStart();                                                // start scrolling, () → void
            Thread.sleep(5000);
            lcd.scrollStop();                                                 // stop scrolling, () → void

            // --- Power down ---
            // Sleep turns the display off; RAM and registers are kept.
            lcd.sleep();                                                      // enter sleep, () → void
        }
    }
}
