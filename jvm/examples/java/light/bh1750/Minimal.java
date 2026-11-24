///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Bh1750Minimal;

public class Minimal {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x23").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Bh1750Minimal(connection);                                                     // Create BH1750 driver, (connection) → Bh1750Minimal
            sensor.init();                                                                                  // Power on, () → void

            while (true) {
                var lux = sensor.read();                                                                    // Measure illuminance, () → double lx
                System.out.printf("%.1f lx%n", lux);
                Thread.sleep(1000);
            }
        }
    }
}
