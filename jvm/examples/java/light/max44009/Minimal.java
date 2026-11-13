///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.MAX44009Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, MAX44009Minimal.DEFAULT_ADDRESS)) {           // open I²C bus, device address, (bus, address=0x4A) → I2CConnection
            var sensor = new MAX44009Minimal(connection);                                          // construct driver, (connection) → MAX44009Minimal
            for (int i = 0; i < 10; i++) {
                System.out.println(sensor.lux() + " lx");                                          // Read illuminance, () → double lux
                Thread.sleep(1000);
            }
        }
    }
}
