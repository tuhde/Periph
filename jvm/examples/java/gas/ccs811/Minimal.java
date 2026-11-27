///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.gas.Ccs811Minimal;

public class Minimal {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x5A").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Ccs811Minimal(connection);                                             // Create CCS811 driver, (connection) → Ccs811Minimal
            sensor.init();                                                                          // Check HW_ID, start application, mode 1, () → void

            while (true) {
                var m = sensor.read();                                                              // Wait for the next sample, () → Measurement {eco2Ppm ppm, tvocPpb ppb}
                System.out.printf("eCO2 %d ppm, TVOC %d ppb%n", m.eco2Ppm(), m.tvocPpb());
            }
        }
    }
}
