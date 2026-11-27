///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Ms5607Minimal;

public class Minimal {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x77").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Ms5607Minimal(connection);                                                             // Create MS5607 driver, (connection) → Ms5607Minimal
            sensor.init();                                                                                          // Reset, read PROM, verify CRC-4, () → void

            while (true) {
                var v = sensor.read();                                                                              // Read temperature and pressure, () → double[] {°C, Pa}
                System.out.printf("%.2f C, %.1f Pa%n", v[0], v[1]);
                Thread.sleep(1000);
            }
        }
    }
}
