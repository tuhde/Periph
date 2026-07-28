///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.display.Pcf8576Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x38"));
        try (var connection = new I2CConnection(bus, addr)) {              // open I²C bus, (bus, address=0x38) → I2CConnection
            var lcd = new Pcf8576Minimal(connection);                      // construct driver, (connection) → Pcf8576Minimal

            int[] digits = {1, 2, 3, 4};
            for (int i = 0; i < digits.length; i++) {
                int seg = Pcf8576Minimal.SEVEN_SEG[digits[i]];            // encode 7-segment digit, (digit 0–9) → int
                lcd.setDigit7seg(i, seg);                                 // write one digit, (position 0–19, segments 0–255) → void
            }
        }
    }
}
