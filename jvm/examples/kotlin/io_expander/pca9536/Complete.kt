///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.PCA9536Full

fun main() {
    I2CConnection(1, 0x41).use { connection ->                        // open I²C bus 1, device 0x41, (bus, address) → I2CConnection
        val chip = PCA9536Full(connection, 0x41)                         // construct driver, (connection, addr=0x41) → PCA9536Full
                                                                          // writes the reset values; all pins start as inputs

        val p0 = chip.pin(0)                                              // get Full pin proxy, (n) → Pin
                                                                          // P0; all four pins are bidirectional
        p0.setOutput()                                                    // set direction to output, () → Unit
                                                                          // latches OUTPUT, then clears the CONFIG bit
        p0.setLow()                                                       // drive low, () → Unit
                                                                          // clears the OUTPUT bit via the shadow
        p0.toggle()                                                       // invert the output latch, () → Unit
                                                                          // one OUTPUT write, no read-modify-write
        val level = p0.read()                                             // read the actual level, () → Boolean
                                                                          // reads INPUT
        println("P0 level: %b".format(level))
        chip.pin(3).setInput()                                            // set P3 as input, () → Unit
                                                                          // sets its CONFIG bit; the weak pull-up is fixed (setPullUp always throws)

        chip.writePort(0, 0x01)                                           // write the output latch, (port=0, mask) → Unit
                                                                          // whole-port OUTPUT write
        val port = chip.readPort(0)                                       // read all 4 pins, (port=0) → Int bitmask
                                                                          // INPUT register
        println("PORT=0x%X".format(port))

        chip.configurePolarity(0x00)                                      // input polarity, (mask) → Unit
                                                                          // 1 bits invert the INPUT read
        chip.setDirection(0x0C)                                           // whole-port direction, (mask) → Unit
                                                                          // P0–P1 outputs, P2–P3 inputs; OUTPUT is written first
        val dir = chip.direction                                          // read the direction shadow, () → Int
                                                                          // 1 = input, 0 = output
        println("CONFIG=0x%X".format(dir))
        val config = chip.readRegister(0x03)                              // read raw register, (reg) → Int
                                                                          // escape hatch for debugging
        println("CONFIG=0x%X".format(config))
        chip.writeRegister(0x02, 0x04)                                    // write raw register, (reg, value) → Unit
                                                                          // POLARITY: invert P2

        chip.watchChanges({ changed -> println("changed=0x%X".format(changed)) }) // start polling for changes, (callback, intervalS=0.005) → Unit
                                                                          // no INT pin exists; a 5 ms thread polls INPUT
        chip.pin(2).watch({ println("P2 changed") })                      // watch P2, (handler, trigger=CHANGE) → Unit
                                                                          // edge filtered in software; all pins share one poll
        val changed = chip.pollChanges()                                // read INPUT, get changed mask, () → Int
                                                                          // XOR with the last INPUT value
        println("changed=0x%X".format(changed))
        chip.pin(2).unwatch()                                             // stop watching, () → Unit
                                                                          // drops the handler
        chip.unwatchChanges()                                               // stop polling, () → Unit
                                                                          // stops the polling thread
        chip.reset()                                                      // restore reset values, () → Unit
                                                                          // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF
    }
}
