///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.io_expander.PCA6408AFull;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x20)) {           // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
            var chip = new PCA6408AFull(connection, 0x20);                    // construct driver, (connection, addr=0x20) → PCA6408AFull
                                                                              // writes the reset values; all pins start as inputs

            var p0 = chip.pin(0);                                             // get Full pin proxy, (n) → Pin
                                                                              // P0; all eight pins are bidirectional
            p0.setOutput();                                                   // set direction to output, () → void
                                                                              // latches OUTPUT, then clears the CONFIG bit
            p0.setLow();                                                      // drive low, () → void
                                                                              // clears the OUTPUT bit via the shadow
            p0.toggle();                                                      // invert the output latch, () → void
                                                                              // one OUTPUT write, no read-modify-write
            boolean level = p0.read();                                        // read the actual level, () → boolean
                                                                              // reads INPUT
            System.out.printf("P0 level: %b%n", level);
            chip.pin(5).setInput();                                           // set P5 as input, () → void
                                                                              // sets its CONFIG bit; the chip has no pull-ups (setPullUp always throws)

            chip.writePort(0, 0x05);                                          // write the output latch, (port=0, mask) → void
                                                                              // whole-port OUTPUT write
            int port = chip.readPort(0);                                      // read all 8 pins, (port=0) → int bitmask
                                                                              // INPUT register
            System.out.printf("PORT=0x%02X%n", port);

            chip.configurePolarity(0x00);                                     // input polarity, (mask) → void
                                                                              // 1 bits invert the INPUT read
            chip.setDirection(0xF0);                                          // whole-port direction, (mask) → void
                                                                              // P0–P3 outputs, P4–P7 inputs; OUTPUT is written first
            int dir = chip.getDirection();                                    // read the direction shadow, () → int
                                                                              // 1 = input, 0 = output
            System.out.printf("CONFIG=0x%02X%n", dir);
            int config = chip.readRegister(0x03);                             // read raw register, (reg) → int
                                                                              // escape hatch for debugging
            System.out.printf("CONFIG=0x%02X%n", config);
            chip.writeRegister(0x02, 0x10);                                   // write raw register, (reg, value) → void
                                                                              // POLARITY: invert P4

            chip.onInterrupt(changed -> System.out.printf("interrupt, changed=0x%02X%n", changed)); // subscribe to INT, (callback[, intPin]) → void
                                                                              // INT line if wired, else 5 ms polling
            chip.pin(4).watch(pin -> System.out.println("P4 changed"));       // watch P4, (handler[, trigger]) → void
                                                                              // edge filtered in software; all pins share INT
            int changed = chip.pollInterrupt();                               // read INPUT, get changed mask, () → int
                                                                              // XOR with the last INPUT value
            System.out.printf("changed=0x%02X%n", changed);
            chip.pin(4).unwatch();                                            // stop watching, () → void
                                                                              // drops the handler
            chip.offInterrupt();                                              // unsubscribe, () → void
                                                                              // stops delivery
            chip.reset();                                                     // restore reset values, () → void
                                                                              // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF
        }
    }
}
