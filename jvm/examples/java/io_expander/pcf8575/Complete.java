///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.io_expander.Pcf8575Minimal;
import it.uhde.periph.chips.io_expander.Pcf8575Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x20)) {           // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
            var chip = new Pcf8575Full(connection);                    // construct full driver, (connection) → Pcf8575Full

            var p0 = chip.pin(0);                                      // get pin proxy, (n=0) → Pin
            p0.setOutput();                                             // set output mode, () → void
            p0.setHigh();                                               // set high (release to quasi-input), () → void
            p0.setLow();                                                // drive low, () → void
            boolean v = p0.read();                                      // read actual level, () → boolean

            chip.writePort(0, 0b00001111);                              // write Port 0, (port=0, mask) → void
            chip.writePort(1, 0b00001111);                              // write Port 1, (port=1, mask) → void

            var p8 = chip.pin(8);                                      // get pin proxy, (n=8) → Pin
            p8.setInput();                                              // set input mode, () → void
            boolean state = p8.read();                                  // read actual level, () → boolean

            chip.onInterrupt(mask ->                                     // subscribe to INT line, (callback) → void
                System.out.println("changed: " + Integer.toBinaryString(mask)));

            int changed = chip.pollInterrupt();                          // read and return 16-bit changed bitmask, () → int
            System.out.println("changed=0x" + Integer.toHexString(changed));

            var p9 = chip.pin(9);                                      // get pin proxy, (n=9) → Pin
            p9.setInput();
            java.util.function.Consumer<Pcf8575Full.Pin> watcher = pin -> {
                System.out.println("P9 changed");                       // called with this pin when its state matches the trigger
            };
            p9.watch(watcher);                                          // subscribe to pin edges, (handler, trigger=CHANGE) → void
            p9.unwatch();                                               // unsubscribe pin handler, () → void

            chip.offInterrupt();                                        // unsubscribe and stop delivery, () → void
            System.out.println("v=" + v + " state=" + state);
        }
    }
}
