///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Bmp384Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x76)) {       // open I²C bus 1, device 0x76, (bus, address=0x76) → I2CConnection
            var sensor = new Bmp384Minimal(connection);                  // construct driver, verifies chip ID and loads calibration, (connection) → Bmp384Minimal

            for (int i = 0; i < 5; i++) {
                double t = sensor.temperature();                        // read temperature, () → double °C
                double p = sensor.pressure();                           // read pressure, () → double hPa
                System.out.printf("temperature=%.2f °C  pressure=%.2f hPa%n", t, p);
                Thread.sleep(1000);
            }
        }
    }
}
