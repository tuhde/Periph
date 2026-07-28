///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.Ina226Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x40)) {            // open I²C bus 1, device 0x40, (bus, address) → I2CConnection
            var ina = new Ina226Minimal(connection, 0.1, 2.0);              // construct driver, (connection, rShunt=0.1 Ω, maxCurrent=2.0 A) → Ina226Minimal

            for (int i = 0; i < 10; i++) {
                double v  = ina.voltage();       // read bus voltage, () → double V
                double vs = ina.shuntVoltage();  // read shunt voltage, () → double V
                double c  = ina.current();       // read current, () → double A
                double p  = ina.power();         // read power, () → double W
                System.out.printf("V=%.3f V  Vshunt=%.6f V  I=%.4f A  P=%.4f W%n", v, vs, c, p);
                Thread.sleep(1000);
            }
        }
    }
}
