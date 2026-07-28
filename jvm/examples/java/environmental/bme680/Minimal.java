///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.environmental.Bme680Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x76"));
        try (var connection = new I2CConnection(bus, addr)) {             // open I²C bus, (bus, address=0x76) → I2CConnection
            var sensor = new Bme680Minimal(connection);                  // construct driver, verifies chip ID and loads calibration, (connection) → Bme680Minimal

            for (int i = 0; i < 5; i++) {
                double t = sensor.temperature();                        // read temperature, () → double °C
                double p = sensor.pressure();                           // read pressure, () → double hPa
                double h = sensor.humidity();                           // read humidity, () → double %RH
                double g = sensor.gasResistance();                      // read gas resistance, () → double Ω
                System.out.printf("temperature=%.2f °C  pressure=%.2f hPa  humidity=%.1f %%RH  gas=%.0f Ω%n", t, p, h, g);
                Thread.sleep(1000);
            }
        }
    }
}
