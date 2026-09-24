///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.imu.MPU9250Minimal;

public class Minimal {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x68").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var imu = new MPU9250Minimal(connection);                           // Create MPU9250 driver, (connection) → void

            while (true) {
                double[] a = imu.accel();                                      // Read 3-axis acceleration, () → double[] m/s²
                double[] g = imu.gyro();                                       // Read 3-axis angular rate, () → double[] rad/s
                System.out.printf("accel: %.2f %.2f %.2f  gyro: %.2f %.2f %.2f%n", a[0], a[1], a[2], g[0], g[1], g[2]);
                Thread.sleep(100);
            }
        }
    }
}