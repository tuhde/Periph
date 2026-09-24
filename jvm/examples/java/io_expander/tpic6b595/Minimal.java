///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.SiPoConnection;
import it.uhde.periph.chips.io_expander.Tpic6b595Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        // Hardware SPI bus 0, device 0; RCK on gpiochip0 line 17; SRCLR/G not wired
        try (var connection = SiPoConnection.hardware(0, 0, 17, -1, -1)) { // open SiPo connection, (bus, device, rckLine, srclrLine, gLine) → SiPoConnection
            var chip = new Tpic6b595Minimal(connection);                       // construct driver, (connection, numDevices=1) → Tpic6b595Minimal
                                                                              // initialises every output to OFF (shadow zeroed, latched once)

            var p0 = chip.pin(0);                                              // get pin proxy, (n=0) → Pin
            var p7 = chip.pin(7);                                              // get pin proxy, (n=7) → Pin

            while (true) {
                p0.setHigh();                                                  // set DRAIN0 ON, () → void
                p7.setLow();                                                   // set DRAIN7 OFF, () → void
                Thread.sleep(500);
                p0.setLow();                                                   // set DRAIN0 OFF, () → void
                p7.setHigh();                                                  // set DRAIN7 ON, () → void
                Thread.sleep(500);
            }
        }
    }
}
