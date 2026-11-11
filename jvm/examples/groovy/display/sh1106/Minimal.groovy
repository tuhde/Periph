///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0
// SH1106 minimal example: 1.3" 128 x 64 OLED module (SH1106, RAM columns 2..129) over I2C on a Raspberry Pi (JVM).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3; address 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD1). Minimal use: a border and a diagonal on the 128 x 64 panel.

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.display.SH1106Minimal

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x3C"
int addr = Integer.decode(addrStr)

I2CConnection conn = new I2CConnection(bus, addr)                             // open I2C connection, (bus, address=0x3C) → I2CConnection
try {
    def lcd = new SH1106Minimal(conn)                                         // construct driver, (connection, width=128, height=64, columnOffset=2, internalDcdc=true) → SH1106Minimal

    lcd.clear()                                                               // clear framebuffer, () → void
    for (int x = 0; x < 128; x++) {
        lcd.setPixel(x, 0, true)                                              // set pixel, (x 0..127, y 0..63, on) → void
        lcd.setPixel(x, 63, true)                                             // set pixel, (x 0..127, y 0..63, on) → void
        lcd.setPixel(x, x.intdiv(2), true)                                    // set pixel, (x 0..127, y 0..63, on) → void
    }
    for (int y = 0; y < 64; y++) {
        lcd.setPixel(0, y, true)                                              // set pixel, (x 0..127, y 0..63, on) → void
        lcd.setPixel(127, y, true)                                            // set pixel, (x 0..127, y 0..63, on) → void
    }
    lcd.show()                                                                // send framebuffer to the display, () → void
} finally {
    conn.close()
}
