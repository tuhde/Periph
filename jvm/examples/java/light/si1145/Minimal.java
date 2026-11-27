///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Si1145Minimal;

public class Minimal {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Si1145Minimal(connection);                                             // Create Si1145 driver, (connection) → Si1145Minimal
            sensor.init();                                                                          // Check PART_ID, HW_KEY, UV + ALS + PS1, () → void

            while (true) {
                var m = sensor.read();                                                              // Forced PS + ALS, () → Reading {proximity, uvIndex, visible, infrared}
                System.out.printf("prox %d  UV %.2f  vis %d  ir %d%n", m.proximity(), m.uvIndex(), m.visible(), m.infrared());
                Thread.sleep(1000);
            }
        }
    }
}
