///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-java:1.2.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.other.Mpr121Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x5A)) {            // Open I2C connection, (bus=1, address=0x5A) → I2CConnection
            var mpr = new Mpr121Minimal(connection);                     // Create MPR121 driver, (connection) → Mpr121Minimal
                                                                       // resets, applies default thresholds (T=12, R=6), enters Run Mode on all 12 electrodes
            for (int i = 0; i < 10; i++) {
                int t = mpr.touched();                                  // Read 12-bit touch bitmask, () → int bitmask
                                                                       // bit n=1 means ELEn is currently touched
                System.out.printf("touched=0x%03X%n", t);
                Thread.sleep(1000);
            }
        }
    }
}
