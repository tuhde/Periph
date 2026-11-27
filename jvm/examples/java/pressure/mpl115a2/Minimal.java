///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.MPL115A2Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x60)) {       // open I²C bus 1, device 0x60, (bus, address=0x60) → I2CConnection
            var sensor = new MPL115A2Minimal(connection);        // construct driver, loads the coefficients, (connection) → MPL115A2Minimal

            while (true) {
                double p = sensor.readPressure();                // read pressure, () → double Pa
                double t = sensor.readTemperature();             // read temperature, () → double °C (approximate)
                System.out.printf("pressure=%.0f Pa  temperature=%.1f °C%n", p, t);
                Thread.sleep(1000);
            }
        }
    }
}
