///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-java:1.5.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.LM25066Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x40)) {   // open I²C bus 1, device 0x40, (bus, address) → I2CConnection
            var hotswap = new LM25066Minimal(connection, 1.0, false);   // construct driver, (connection, rSenseMohm, currentLimitHigh) → LM25066Minimal

            for (int i = 0; i < 10; i++) {
                System.out.println(hotswap.voltageIn());                                                  // read VIN, () → double V
                System.out.println(hotswap.voltageOut());                                                 // read VOUT, () → double V
                System.out.println(hotswap.current());                                                    // read input current, () → double A
                System.out.println(hotswap.power());                                                      // read input power, () → double W
                System.out.println(hotswap.temperature());                                                // read MOSFET temperature, () → double °C
                Thread.sleep(1000);
            }
        }
    }
}
