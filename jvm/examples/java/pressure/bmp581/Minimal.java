///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Bmp581Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x46)) {       // open I²C bus 1, device 0x46, (bus, address=0x46) → I2CConnection
            var sensor = new Bmp581Minimal(connection);                // construct driver, verifies chip ID, (connection) → Bmp581Minimal

            while (true) {
                double t = sensor.temperature();                        // read temperature, () → double °C
                double p = sensor.pressure();                           // read pressure, () → double Pa
                System.out.printf("temperature=%.2f °C  pressure=%.1f Pa%n", t, p);
                Thread.sleep(1000);
            }
        }
    }
}