///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.LSM303Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        try (var accelConn = new I2CConnection(bus, LSM303Minimal.I2C_ADDRESS_ACCEL);
             var magConn = new I2CConnection(bus, LSM303Minimal.I2C_ADDRESS_MAG)) {
            var compass = new LSM303Minimal(accelConn, magConn);   // Create LSM303 driver, (accel_connection, mag_connection)
            for (int i = 0; i < 10; i++) {
                double[] accel = compass.readAcceleration();        // Read acceleration, () → [g, g, g]
                double[] mag = compass.readMagnetic();               // Read magnetic field, () → [µT, µT, µT]
                System.out.printf("accel x=%.4f y=%.4f z=%.4f g%n", accel[0], accel[1], accel[2]);
                System.out.printf("mag   x=%.2f y=%.2f z=%.2f uT%n", mag[0], mag[1], mag[2]);
                Thread.sleep(1000);
            }
        }
    }
}
