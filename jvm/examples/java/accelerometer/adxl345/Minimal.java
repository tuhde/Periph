///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.Adxl345Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x53").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {  // open I²C bus, device address, (bus, address) → I2CConnection
            var sensor = new Adxl345Minimal(connection);          // construct driver and verify DEVID, (connection) → Adxl345Minimal

            for (int i = 0; i < 10; i++) {
                double[] xyz = sensor.read();                      // Read 3-axis acceleration, () → double[3] g, g, g
                System.out.printf("x=%.3f y=%.3f z=%.3f g%n", xyz[0], xyz[1], xyz[2]);
                Thread.sleep(100);
            }
        }
    }
}