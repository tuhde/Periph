///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.LSM303Full;
import it.uhde.periph.chips.accelerometer.LSM303Minimal;
import it.uhde.periph.chips.accelerometer.LSM303Source;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        try (var accelConnection = new I2CConnection(bus, 0x19);
             var magConnection = new I2CConnection(bus, 0x1E)) {
            var chip = new LSM303Minimal(accelConnection, magConnection);   // Create LSM303 driver, (accelConnection, magConnection) → void

            for (int i = 0; i < 10; i++) {
                double[] a = chip.readAcceleration();                        // Read 3-axis acceleration, () → double[] g
                double[] m = chip.readMagnetic();                            // Read 3-axis magnetic field, () → double[] µT
                System.out.printf("a=(%.3f, %.3f, %.3f) g  m=(%.1f, %.1f, %.1f) µT%n", a[0], a[1], a[2], m[0], m[1], m[2]);
                Thread.sleep(100);
            }
        }
    }
}
