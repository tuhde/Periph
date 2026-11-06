///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-java:1.3.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.display.PCF8578Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x3C"));
        try (var connection = new I2CConnection(bus, addr)) {   // open I²C bus, (bus, address=0x3C) → I2CConnection
            var lcd = new PCF8578Minimal(connection, 16);   // construct driver, (connection, rows=16 8/16/24/32) → PCF8578Minimal

            lcd.clear();   // clear display RAM, () → void
            lcd.setPixel(0, 0, true);   // set one pixel, (x 0–23, y 0–15, on) → void
            lcd.setPixel(23, 15, true);   // set opposite corner, (x 0–23, y 0–15, on) → void
            lcd.writeColumns(4, 0, new byte[]{(byte) 0xFF, (byte) 0x81, (byte) 0x81, (byte) 0xFF});   // write column bytes, (x 0–23, bank 0–1, data bytes) → void
        }
    }
}
