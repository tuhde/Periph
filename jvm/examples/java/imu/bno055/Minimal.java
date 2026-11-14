///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.imu.BNO055Minimal;

public class Minimal {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x29").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var imu = new BNO055Minimal(connection);                                        // Create BNO055 driver, (connection) → void

            while (true) {
                double[] e = imu.euler();                                                   // Read fused Euler angles, () → double[] rad (heading, roll, pitch)
                System.out.printf("heading %.2f rad, roll %.2f, pitch %.2f%n", e[0], e[1], e[2]);
                Thread.sleep(100);
            }
        }
    }
}
