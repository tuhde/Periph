///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-java:1.5.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Apds9250Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, Apds9250Minimal.DEFAULT_ADDRESS)) {           // open I²C bus, device address, (bus, address=0x52) → I2CConnection
            var sensor = new Apds9250Minimal(connection);                                           // construct driver, (connection) → Apds9250Minimal
            for (int i = 0; i < 10; i++) {
                var c = sensor.read();                                                              // Read the four channels, () → Counts {red, green, blue, ir}
                System.out.println("R " + (c.red) + " G " + (c.green) + " B " + (c.blue) + " IR " + (c.ir));
                Thread.sleep(1000);
            }
        }
    }
}
