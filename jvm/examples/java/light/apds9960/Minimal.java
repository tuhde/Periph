///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Apds9960Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x39)) {            // open I²C bus 1, device 0x39, (bus, address) → I2CConnection
            var apds = new Apds9960Minimal(connection);                     // construct driver, (connection) → Apds9960Minimal

            for (int i = 0; i < 10; i++) {
                int[] rgbc = apds.color();                                 // read all RGBC channels, () → int[] [clear, red, green, blue]
                System.out.printf("C=%d R=%d G=%d B=%d%n", rgbc[0], rgbc[1], rgbc[2], rgbc[3]);
                Thread.sleep(1000);
            }
        }
    }
}
