///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.io_expander.Pcf8574Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x20)) {           // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
            var chip = new Pcf8574Full(connection);                    // construct full driver, (connection) → Pcf8574Full
                                                                       // initialises shadow to 0xFF; all pins input mode

            int port = chip.readPort();                                // read all 8 pins, () → int bitmask
                                                                       // returns actual logic levels on P0–P7 regardless of shadow
            System.out.printf("initial port = 0x%02X%n", port);

            chip.writePort(0x0F);                                      // write all 8 pins, (mask) → void
                                                                       // P0–P3 driven low, P4–P7 remain input mode; updates shadow

            var p7 = chip.pin(7);                                      // get pin proxy, (n) → Pin
                                                                       // returns a Pin for P7 backed by the driver shadow
            p7.setInput();                                             // set input mode — write 1 to shadow bit 7, () → void
                                                                       // releases P7 to quasi-input; external signal drives it
            p7.setOutput();                                            // set output mode — drive P7 low, () → void
                                                                       // PCF8574 has no push-pull high; setHigh releases to pull-up
            p7.setHigh();                                              // release to quasi-input (write 1), () → void
                                                                       // enables internal ~100 µA current source; sufficient for logic in
            p7.setLow();                                               // drive low (write 0), () → void
                                                                       // open-drain sink up to 25 mA; active-low LED turns on

            boolean v = p7.read();                                     // read actual pin level, () → boolean
                                                                       // reads bus, not shadow — reflects external pull or driven level
            System.out.println("P7 = " + v);

            p7.toggle();                                               // invert shadow bit 7, () → void
                                                                       // if last written 1 → writes 0; if last written 0 → writes 1

            chip.onInterrupt(mask -> {                                 // subscribe to INT line, (IntConsumer) → void
                                                                       // uses connection.intPin() if wired, else a 5 ms polling thread
                System.out.printf("changed pins: 0x%02X%n", mask);
            });

            Thread.sleep(100);

            int changed = chip.pollInterrupt();                        // read port and return changed-pin bitmask, () → int
                                                                       // compares current read to last read; clears chip INT line
            System.out.printf("polled interrupt: 0x%02X%n", changed);

            var p5 = chip.pin(5);                                      // get pin proxy, (n) → Pin
            p5.setInput();
            java.util.function.Consumer<Pcf8574Full.Pin> watcher = pin -> {
                System.out.println("P5 changed");                      // called with this pin when its state matches the trigger
            };
            p5.watch(watcher);                                         // subscribe to pin edges, (handler, trigger=CHANGE) → void
                                                                       // fires whenever P5 changes; needs onInterrupt armed first
            p5.unwatch();                                              // unsubscribe pin handler, () → void

            chip.offInterrupt();                                       // unsubscribe and stop delivery, () → void
        }
    }
}
