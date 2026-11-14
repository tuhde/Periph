///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.memory.CAT24C512Minimal;

public class Minimal {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x50").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr, null, null, 2)) {
            var ee = new CAT24C512Minimal(connection);                                      // Create CAT24C512 driver, (connection) → void

            int counter = 0;
            while (true) {
                ee.writeByte(0x0000, counter);                                              // Write a single byte, (address 0-65535, value 0-255) → void
                int v = ee.readByte(0x0000);                                                // Read a single byte, (address 0-65535) → int
                System.out.printf("Stored: %d%n", v);
                counter = (counter + 1) & 0xFF;
                Thread.sleep(1000);
            }
        }
    }
}
