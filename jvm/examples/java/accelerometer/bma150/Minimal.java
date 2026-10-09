///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.Bma150Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x38").replaceFirst("^0[xX]", ""), 16);
        try (var connection = new I2CConnection(bus, addr)) {
            var accel = new Bma150Minimal(connection);          // Create BMA150 driver, (connection)
            for (int i = 0; i < 10; i++) {
                double[] xyz = accel.read();                  // Read 3-axis acceleration, () → [g, g, g]
                System.out.printf("x=%.3f y=%.3f z=%.3f g%n", xyz[0], xyz[1], xyz[2]);
                Thread.sleep(100);
            }
        }
    }
}
