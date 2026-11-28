///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.TSL2561Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, TSL2561Minimal.DEFAULT_ADDRESS)) {            // open I²C bus, device address, (bus, address=0x39) → I2CConnection
            var sensor = new TSL2561Minimal(connection);                                           // construct driver, (connection) → TSL2561Minimal
            for (int i = 0; i < 10; i++) {
                System.out.printf("%.1f lx%n", sensor.readLux());                                  // Read illuminance, () → double lux
                Thread.sleep(1000);
            }
        }
    }
}
