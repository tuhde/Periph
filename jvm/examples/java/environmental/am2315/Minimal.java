///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.environmental.AM2315Minimal;

public class Minimal {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x5C").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new AM2315Minimal(connection);                              // Create AM2315 driver, (connection) → void

            while (true) {
                double[] v = sensor.read();                                              // Read temperature and humidity, () → double[] {°C, %RH}
                // previous measurement; reads are kept >= 2 s apart
                System.out.printf("%.1f C, %.1f %%RH%n", v[0], v[1]);
            }
        }
    }
}
