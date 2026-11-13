///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.io_expander.PCA9539Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x74)) {           // open I²C bus 1, device 0x74, (bus, address) → I2CConnection
            var chip = new PCA9539Full(connection, 0x74);                    // construct driver, (connection, addr=0x74) → PCA9539Full
                                                                              // writes the reset values; all pins start as inputs

            var p0 = chip.pin(0);                                             // get Full pin proxy, (n) → Pin
                                                                              // P00; all sixteen pins are bidirectional
            p0.setOutput();                                                   // set direction to output, () → void
                                                                              // latches OUTPUT, then clears the CONFIG bit
            p0.setLow();                                                      // drive low, () → void
                                                                              // clears the OUTPUT bit via the shadow
            p0.toggle();                                                      // invert the output latch, () → void
                                                                              // one OUTPUT write, no read-modify-write
            boolean level = p0.read();                                        // read the actual level, () → boolean
                                                                              // reads INPUT
            System.out.printf("P0 level: %b%n", level);
            chip.pin(5).setInput();                                           // set P05 as input, () → void
                                                                              // sets its CONFIG bit; the chip has no pull-ups (setPullUp always throws)

            chip.writePort(0, 0x05);                                          // write port 0 output latch, (port, mask) → void
                                                                              // single-port OUTPUT write, updates the shadow
            chip.writeAll(0xA505);                                            // write both output latches, (value) → void
                                                                              // one two-byte write at OUTPUT0 (register-pair toggling)
            int port = chip.readPort(1);                                      // read port 1 pins, (port) → int bitmask
                                                                              // INPUT1 register
            System.out.printf("PORT1=0x%02X%n", port);
            int all = chip.readAll();                                           // read all 16 pins, () → int
                                                                                // one two-byte read at INPUT0
            System.out.printf("ALL=0x%04X%n", all);

            chip.configurePolarity(0x0000);                                     // input polarity, (mask) → void
                                                                              // 1 bits invert the INPUT read
            chip.setDirection(0xFF00);                                          // whole-device direction, (mask) → void
                                                                              // port 0 outputs, port 1 inputs; OUTPUT is written first
            int dir = chip.getDirection();                                    // read the direction shadow, () → int
                                                                              // 1 = input, 0 = output
            System.out.printf("CONFIG=0x%04X%n", dir);
            int config = chip.readRegister(0x07);                             // read raw register, (reg) → int
                                                                              // escape hatch for debugging
            System.out.printf("CONFIG1=0x%02X%n", config);
            chip.writeRegister(0x05, 0x10);                                   // write raw register, (reg, value) → void
                                                                              // POLARITY1: invert P14

            chip.onInterrupt(changed -> System.out.printf("interrupt, changed=0x%04X%n", changed)); // subscribe to INT, (callback[, intPin]) → void
                                                                              // INT line if wired, else 5 ms polling
            chip.pin(12).watch(pin -> System.out.println("P14 changed"));       // watch P14, (handler[, trigger]) → void
                                                                              // edge filtered in software; all pins share INT
            int changed = chip.pollInterrupt();                               // read INPUT0/1, get changed mask, () → int
                                                                              // XOR with the last INPUT values
            System.out.printf("changed=0x%04X%n", changed);
            chip.pin(12).unwatch();                                            // stop watching, () → void
                                                                              // drops the handler
            chip.offInterrupt();                                              // unsubscribe, () → void
                                                                              // stops delivery
            chip.reset();                                                     // restore reset values, () → void
                                                                              // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF (both ports)
        }
    }
}
