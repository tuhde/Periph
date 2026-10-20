///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1
// UC1611s minimal example: EA DOGXL240-7 (240 x 128, 3.3 V only) over I2C on a Raspberry Pi (JVM).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VDD = 3V3; module pins BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39.
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 2-4.7 k to 3.3 V. Minimal use: a border and a diagonal on the 240 x 128 panel.

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.display.Uc1611sMinimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x38"));
        try (var command = new I2CConnection(bus, addr);                          // open command connection, (bus, address=0x38) → I2CConnection
             var data = new I2CConnection(bus, addr + 1)) {                       // open data connection, (bus, address=0x39) → I2CConnection
            var lcd = new Uc1611sMinimal(command, data);                         // construct driver, (command, data, width=240, height=128) → Uc1611sMinimal

            lcd.clear();                                                      // clear framebuffer, () → void
            for (int x = 0; x < 240; x++) {
                lcd.setPixel(x, 0, true);                                     // set pixel, (x 0..239, y 0..127, on) → void
                lcd.setPixel(x, 127, true);                                   // set pixel, (x 0..239, y 0..127, on) → void
                lcd.setPixel(x, x * 127 / 239, true);                         // set pixel, (x 0..239, y 0..127, on) → void
            }
            for (int y = 0; y < 128; y++) {
                lcd.setPixel(0, y, true);                                     // set pixel, (x 0..239, y 0..127, on) → void
                lcd.setPixel(239, y, true);                                   // set pixel, (x 0..239, y 0..127, on) → void
            }
            lcd.show();                                                       // send framebuffer to the display, () → void
        }
    }
}
