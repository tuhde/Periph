///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.led.SAA1064Minimal;
import java.util.Map;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x38"));
        try (var connection = new I2CConnection(bus, addr)) {                      // open I²C bus, (bus, address=0x38) → I2CConnection
            var display = new SAA1064Minimal(connection);               // construct driver, (connection) → SAA1064Minimal
            for (int n = 0; n < 100; n += 1) {
                display.showNumber(n);                                  // show a right-aligned number, (value 0–9999) → void
                Thread.sleep(100);
            }
        }
    }
}
