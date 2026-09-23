///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-java:1.2.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.temperature.MCP9808Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, MCP9808Minimal.DEFAULT_ADDRESS)) {   // open I²C bus, device address, (bus, address=0x18) → I2CConnection
            var sensor = new MCP9808Minimal(connection);                                  // construct driver and check identity, (connection) → MCP9808Minimal

            for (int i = 0; i < 10; i++) {
                var t = sensor.readTemperature();                                         // Read ambient temperature, () → double °C
                System.out.printf("%.4f °C%n", t);
                Thread.sleep(1000);
            }
        }
    }
}
