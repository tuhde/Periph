///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Si1132Minimal;

public class Minimal {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Si1132Minimal(connection);                                                       // Create Si1132 driver, (connection) → void

            while (true) {
            double[] m = sensor.read();                                                                       // Read UV index and ALS counts, () → double[] {UV index, visible counts, infrared counts}
            System.out.printf("UV %.2f, visible %.0f, infrared %.0f%n", m[0], m[1], m[2]);
            Thread.sleep(1000);
            }
        }
    }
}
