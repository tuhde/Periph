///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rtc.PCF8523Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, PCF8523Minimal.DEFAULT_ADDRESS)) {  // open I²C bus, fixed device address, (bus, address) → I2CConnection
            var rtc = new PCF8523Minimal(connection);                                      // construct driver, enable battery backup, (connection) → PCF8523Minimal

            for (int i = 0; i < 10; i++) {
                var dt = rtc.getDatetime();                                                  // Read calendar clock, () → DateTime
                System.out.printf("%04d-%02d-%02d %02d:%02d:%02d%n",
                        dt.year(), dt.month(), dt.day(), dt.hour(), dt.minute(), dt.second());
                Thread.sleep(1000);
            }
        }
    }
}
