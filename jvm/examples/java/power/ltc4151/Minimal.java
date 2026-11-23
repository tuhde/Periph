///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-java:1.5.2

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.Ltc4151Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x6A)) { // open I²C bus 1, device 0x6A, (bus, address) → I2CConnection
            var ltc = new Ltc4151Minimal(connection, 0.02);  // construct driver, (connection, rSense=0.02 Ω) → Ltc4151Minimal

            for (int i = 0; i < 10; i++) {
                double v = ltc.voltage();                   // read supply voltage, () → double V
                double c = ltc.current();                   // read load current, () → double A
                double p = ltc.power();                     // read load power, () → double W
                System.out.printf("V=%.3f V  I=%.4f A  P=%.3f W%n", v, c, p);
                Thread.sleep(1000);
            }
        }
    }
}
