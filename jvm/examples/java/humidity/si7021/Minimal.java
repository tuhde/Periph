///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.Si7021Minimal;

public class Minimal {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Si7021Minimal(connection);                                             // Create Si7021 driver, (connection) → void

            while (true) {
                double[] v = sensor.read();                                                 // Read temperature and humidity, () → double[] {°C, %RH}
                System.out.printf("%.2f C, %.1f %%RH%n", v[0], v[1]);
                Thread.sleep(2000);
            }
        }
    }
}
