///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-java:1.5.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.HDC1008Minimal;

public class Minimal {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new HDC1008Minimal(connection);                                             // Create HDC1008 driver, (connection) → void
            sensor.init();                                                                          // Write default configuration, () → void

            while (true) {
                double[] v = sensor.read();                                                 // Read temperature and humidity, () → double[] {°C, %RH}
                System.out.printf("%.2f C, %.1f %%RH%n", v[0], v[1]);
                Thread.sleep(2000);
            }
        }
    }
}
