///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0
// SSD1305 minimal example: 1.3" 128 x 64 OLED module (SSD1305, RAM columns 4..131) over I2C on a Raspberry Pi (JVM).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3 (OLED supply VCC 7..15 V from the module, no charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Minimal use: a border and a diagonal on the 128 x 64 panel.

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.display.SSD1305Minimal

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x3C
    I2CConnection(bus, addr).use { conn ->                                    // open I2C connection, (bus, address=0x3C) → I2CConnection
        val lcd = SSD1305Minimal(conn)                                         // construct driver, (connection, width=128, height=64, columnOffset=4) → SSD1305Minimal

        lcd.clear()                                                           // clear framebuffer, () → void
        for (x in 0 until 128) {
            lcd.setPixel(x, 0, true)                                          // set pixel, (x 0..127, y 0..63, on) → void
            lcd.setPixel(x, 63, true)                                         // set pixel, (x 0..127, y 0..63, on) → void
            lcd.setPixel(x, x / 2, true)                                      // set pixel, (x 0..127, y 0..63, on) → void
        }
        for (y in 0 until 64) {
            lcd.setPixel(0, y, true)                                          // set pixel, (x 0..127, y 0..63, on) → void
            lcd.setPixel(127, y, true)                                        // set pixel, (x 0..127, y 0..63, on) → void
        }
        lcd.show()                                                            // send framebuffer to the display, () → void
    }
}
