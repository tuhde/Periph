///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0
// SSD1306 minimal example: 0.96" 128 x 64 OLED module (SSD1306) over I2C on a Raspberry Pi (JVM).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3 (the module generates the OLED supply with the SSD1306 charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Minimal use: a border and a diagonal on the 128 x 64 panel.

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.display.SSD1306Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x3C"));
        try (var conn = new I2CConnection(bus, addr)) {                       // open I2C connection, (bus, address=0x3C) → I2CConnection
            var lcd = new SSD1306Minimal(conn);                                // construct driver, (connection, width=128, height=64, externalVcc=false) → SSD1306Minimal

            lcd.clear();                                                      // clear framebuffer, () → void
            for (int x = 0; x < 128; x++) {
                lcd.setPixel(x, 0, true);                                     // set pixel, (x 0..127, y 0..63, on) → void
                lcd.setPixel(x, 63, true);                                    // set pixel, (x 0..127, y 0..63, on) → void
                lcd.setPixel(x, x / 2, true);                                 // set pixel, (x 0..127, y 0..63, on) → void
            }
            for (int y = 0; y < 64; y++) {
                lcd.setPixel(0, y, true);                                     // set pixel, (x 0..127, y 0..63, on) → void
                lcd.setPixel(127, y, true);                                   // set pixel, (x 0..127, y 0..63, on) → void
            }
            lcd.show();                                                       // send framebuffer to the display, () → void
        }
    }
}
