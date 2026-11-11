///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.io_expander.PCA6408AMinimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x20)) {           // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
            var chip = new PCA6408AMinimal(connection, 0x20);                 // construct driver, (connection, addr=0x20) → PCA6408AMinimal

            var p0 = chip.pin(0);                                             // get pin proxy, (n) → Pin
            var p7 = chip.pin(7);                                             // get pin proxy, (n) → Pin
            p0.setInput();                                                    // set P0 as input, () → void
            p7.setOutput();                                                   // set P7 as output, () → void

            while (true) {
                int port = chip.readPort(0);                                  // read all 8 pins, (port=0) → int bitmask
                boolean level = p0.read();                                    // read the actual pin level, () → boolean
                if (level) p7.setHigh();                                      // drive P7 high, () → void
                else       p7.setLow();                                       // drive P7 low, () → void
                System.out.printf("PORT=0x%02X  P0=%b%n", port, level);
                Thread.sleep(200);
            }
        }
    }
}
