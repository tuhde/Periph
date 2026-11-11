///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.PCA6408AFull

fun main() {
    I2CConnection(1, 0x20).use { connection ->                        // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
        val chip = PCA6408AFull(connection, 0x20)                         // construct driver, (connection, addr=0x20) → PCA6408AFull
                                                                          // writes the reset values; all pins start as inputs

        val p0 = chip.pin(0)                                              // get Full pin proxy, (n) → Pin
                                                                          // P0; all eight pins are bidirectional
        p0.setOutput()                                                    // set direction to output, () → Unit
                                                                          // latches OUTPUT, then clears the CONFIG bit
        p0.setLow()                                                       // drive low, () → Unit
                                                                          // clears the OUTPUT bit via the shadow
        p0.toggle()                                                       // invert the output latch, () → Unit
                                                                          // one OUTPUT write, no read-modify-write
        val level = p0.read()                                             // read the actual level, () → Boolean
                                                                          // reads INPUT
        println("P0 level: %b".format(level))
        chip.pin(5).setInput()                                            // set P5 as input, () → Unit
                                                                          // sets its CONFIG bit; the chip has no pull-ups (setPullUp always throws)

        chip.writePort(0, 0x05)                                           // write the output latch, (port=0, mask) → Unit
                                                                          // whole-port OUTPUT write
        val port = chip.readPort(0)                                       // read all 8 pins, (port=0) → Int bitmask
                                                                          // INPUT register
        println("PORT=0x%02X".format(port))

        chip.configurePolarity(0x00)                                      // input polarity, (mask) → Unit
                                                                          // 1 bits invert the INPUT read
        chip.setDirection(0xF0)                                           // whole-port direction, (mask) → Unit
                                                                          // P0–P3 outputs, P4–P7 inputs; OUTPUT is written first
        val dir = chip.direction                                          // read the direction shadow, () → Int
                                                                          // 1 = input, 0 = output
        println("CONFIG=0x%02X".format(dir))
        val config = chip.readRegister(0x03)                              // read raw register, (reg) → Int
                                                                          // escape hatch for debugging
        println("CONFIG=0x%02X".format(config))
        chip.writeRegister(0x02, 0x10)                                    // write raw register, (reg, value) → Unit
                                                                          // POLARITY: invert P4

        chip.onInterrupt({ changed -> println("interrupt, changed=0x%02X".format(changed)) }) // subscribe to INT, (callback, intPin=null) → Unit
                                                                          // INT line if wired, else 5 ms polling
        chip.pin(4).watch({ println("P4 changed") })                      // watch P4, (handler, trigger=CHANGE) → Unit
                                                                          // edge filtered in software; all pins share INT
        val changed = chip.pollInterrupt()                                // read INPUT, get changed mask, () → Int
                                                                          // XOR with the last INPUT value
        println("changed=0x%02X".format(changed))
        chip.pin(4).unwatch()                                             // stop watching, () → Unit
                                                                          // drops the handler
        chip.offInterrupt()                                               // unsubscribe, () → Unit
                                                                          // stops delivery
        chip.reset()                                                      // restore reset values, () → Unit
                                                                          // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF
    }
}
