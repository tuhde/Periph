///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.imu.MPU6050Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x68)) {
            var imu = new MPU6050Minimal(connection);               // Create MPU6050 driver, (connection, addr=0x68) → None
            for (int i = 0; i < 100; i++) {
                double[] a = imu.accel();                          // Read 3-axis acceleration, () → double[] m/s²
                double[] g = imu.gyro();                           // Read 3-axis angular rate, () → double[] rad/s
                System.out.printf("accel: %.2f %.2f %.2f  gyro: %.2f %.2f %.2f%n",
                        a[0], a[1], a[2], g[0], g[1], g[2]);
                Thread.sleep(100);
            }
        }
    }
}
