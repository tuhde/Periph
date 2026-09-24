///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Bmp085Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x77)) {       // open I²C bus 1, device 0x77, (bus, address=0x77) → I2CConnection
            var sensor = new Bmp085Minimal(connection);                  // construct driver, verifies chip ID and loads calibration, (connection) → Bmp085Minimal

            while (true) {
                double t = sensor.temperature();                        // read temperature, () → double °C
                double p = sensor.pressure();                           // read pressure, () → double Pa
                System.out.printf("temperature=%.2f °C  pressure=%.2f Pa%n", t, p);
                Thread.sleep(1000);
            }
        }
    }
}