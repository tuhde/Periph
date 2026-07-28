///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.comms.Rda5807mMinimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x10)) {              // open I²C bus 1, device 0x10, (bus, address) → I2CConnection
            var fm = new Rda5807mMinimal(connection, 100.0, 8);          // construct driver, (connection, frequencyMhz=100.0, volume=8) → Rda5807mMinimal

            while (true) {
                Double freq = fm.seek(true);                            // seek to next station, (up=true) → Double or null
                if (freq != null) {
                    System.out.printf("%.2f MHz%n", freq);
                }
                Thread.sleep(3000);
            }
        }
    }
}
