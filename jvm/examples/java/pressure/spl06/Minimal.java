///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Spl06Minimal;

public class Minimal {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x77").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Spl06Minimal(connection);                                              // Create SPL06 driver, (connection) → Spl06Minimal
            sensor.init();                                                                          // Check ID, read coefficients, apply defaults, () → void
            
            while (true) {
                var m = sensor.read();                                                              // Measure temperature and pressure, () → Measurement {temperatureC °C, pressurePa Pa}
                System.out.printf("%.2f Pa, %.2f C%n", m.pressurePa(), m.temperatureC());
                Thread.sleep(1000);
            }
        }
    }
}
