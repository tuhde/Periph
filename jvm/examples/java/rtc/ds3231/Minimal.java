///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-java:1.2.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rtc.DS3231Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, DS3231Minimal.DEFAULT_ADDRESS)) {  // open I²C bus, fixed device address, (bus, address) → I2CConnection
            var rtc = new DS3231Minimal(connection);                                       // construct driver and confirm presence, (connection) → DS3231Minimal

            for (int i = 0; i < 10; i++) {
                var dt = rtc.getDatetime();                                                  // Read calendar clock, () → DateTime
                double tempC = rtc.readTemperature();                                        // Read temperature, () → double C
                System.out.printf("%04d-%02d-%02d (wd=%d) %02d:%02d:%02d  %.2f C%n",
                        dt.year(), dt.month(), dt.day(), dt.weekday(), dt.hour(), dt.minute(), dt.second(), tempC);
                Thread.sleep(1000);
            }
        }
    }
}
