///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.color.Isl29125Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, Isl29125Minimal.DEFAULT_ADDRESS)) {           // open I²C bus, device address, (bus, address=0x44) → I2CConnection
            var sensor = new Isl29125Minimal(connection);                                           // construct driver, (connection) → Isl29125Minimal
            for (int i = 0; i < 10; i++) {
                var c = sensor.read();                                                              // Read the three channels, () → Counts {red, green, blue}
                System.out.println("R " + (c.red) + " G " + (c.green) + " B " + (c.blue));
                Thread.sleep(1000);
            }
        }
    }
}
