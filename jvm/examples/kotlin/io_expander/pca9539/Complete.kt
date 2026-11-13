///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.PCA9539Full

fun main() {
    I2CConnection(1, 0x74).use { connection ->                        // open I²C bus 1, device 0x74, (bus, address) → I2CConnection
        val chip = PCA9539Full(connection, 0x74)                         // construct driver, (connection, addr=0x74) → PCA9539Full
                                                                          // writes the reset values; all pins start as inputs

        val p0 = chip.pin(0)                                              // get Full pin proxy, (n) → Pin
                                                                          // P00; all sixteen pins are bidirectional
        p0.setOutput()                                                    // set direction to output, () → Unit
                                                                          // latches OUTPUT, then clears the CONFIG bit
        p0.setLow()                                                       // drive low, () → Unit
                                                                          // clears the OUTPUT bit via the shadow
        p0.toggle()                                                       // invert the output latch, () → Unit
                                                                          // one OUTPUT write, no read-modify-write
        val level = p0.read()                                             // read the actual level, () → Boolean
                                                                          // reads INPUT
        println("P0 level: %b".format(level))
        chip.pin(5).setInput()                                            // set P05 as input, () → Unit
                                                                          // sets its CONFIG bit; the chip has no pull-ups (setPullUp always throws)

        chip.writePort(0, 0x05)                                           // write port 0 output latch, (port, mask) → Unit
                                                                          // single-port OUTPUT write, updates the shadow
        chip.writeAll(0xA505)                                             // write both output latches, (value) → Unit
                                                                          // one two-byte write at OUTPUT0 (register-pair toggling)
        val port = chip.readPort(1)                                       // read port 1 pins, (port) → Int bitmask
                                                                          // INPUT1 register
        println("PORT1=0x%02X".format(port))
        val all = chip.readAll()                                                // read all 16 pins, () → Int
                                                                                // one two-byte read at INPUT0
        println("ALL=0x%04X".format(all))

        chip.configurePolarity(0x0000)                                      // input polarity, (mask) → Unit
                                                                          // 1 bits invert the INPUT read
        chip.setDirection(0xFF00)                                           // whole-device direction, (mask) → Unit
                                                                          // port 0 outputs, port 1 inputs; OUTPUT is written first
        val dir = chip.direction                                          // read the direction shadow, () → Int
                                                                          // 1 = input, 0 = output
        println("CONFIG=0x%04X".format(dir))
        val config = chip.readRegister(0x07)                              // read raw register, (reg) → Int
                                                                          // escape hatch for debugging
        println("CONFIG1=0x%02X".format(config))
        chip.writeRegister(0x05, 0x10)                                    // write raw register, (reg, value) → Unit
                                                                          // POLARITY1: invert P14

        chip.onInterrupt({ changed -> println("interrupt, changed=0x%04X".format(changed)) }) // subscribe to INT, (callback, intPin=null) → Unit
                                                                          // INT line if wired, else 5 ms polling
        chip.pin(12).watch({ println("P14 changed") })                      // watch P14, (handler, trigger=CHANGE) → Unit
                                                                          // edge filtered in software; all pins share INT
        val changed = chip.pollInterrupt()                                // read INPUT0/1, get changed mask, () → Int
                                                                          // XOR with the last INPUT values
        println("changed=0x%04X".format(changed))
        chip.pin(12).unwatch()                                             // stop watching, () → Unit
                                                                          // drops the handler
        chip.offInterrupt()                                               // unsubscribe, () → Unit
                                                                          // stops delivery
        chip.reset()                                                      // restore reset values, () → Unit
                                                                          // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF (both ports)
    }
}
