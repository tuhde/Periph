///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.PCA9536Full
import java.util.function.Consumer
import java.util.function.IntConsumer

def connection = new I2CConnection(1, 0x41)           // open I²C bus 1, device 0x41, (bus, address) → I2CConnection
try {
    def chip = new PCA9536Full(connection, 0x41)                         // construct driver, (connection, addr=0x41) → PCA9536Full
                                                                          // writes the reset values; all pins start as inputs

    var p0 = chip.pin(0)                                                  // get Full pin proxy, (n) → Pin
                                                                          // P0; all four pins are bidirectional
    p0.setOutput()                                                        // set direction to output, () → void
                                                                          // latches OUTPUT, then clears the CONFIG bit
    p0.setLow()                                                           // drive low, () → void
                                                                          // clears the OUTPUT bit via the shadow
    p0.toggle()                                                           // invert the output latch, () → void
                                                                          // one OUTPUT write, no read-modify-write
    boolean level = p0.read()                                             // read the actual level, () → boolean
                                                                          // reads INPUT
    println("P0 level: %b".format(level))
    chip.pin(3).setInput()                                                // set P3 as input, () → void
                                                                          // sets its CONFIG bit; the weak pull-up is fixed (setPullUp always throws)

    chip.writePort(0, 0x01)                                               // write the output latch, (port=0, mask) → void
                                                                          // whole-port OUTPUT write
    int port = chip.readPort(0)                                           // read all 4 pins, (port=0) → int bitmask
                                                                          // INPUT register
    println("PORT=0x%X".format(port))

    chip.configurePolarity(0x00)                                          // input polarity, (mask) → void
                                                                          // 1 bits invert the INPUT read
    chip.setDirection(0x0C)                                               // whole-port direction, (mask) → void
                                                                          // P0–P1 outputs, P2–P3 inputs; OUTPUT is written first
    int dir = chip.getDirection()                                         // read the direction shadow, () → int
                                                                          // 1 = input, 0 = output
    println("CONFIG=0x%X".format(dir))
    int config = chip.readRegister(0x03)                                  // read raw register, (reg) → int
                                                                          // escape hatch for debugging
    println("CONFIG=0x%X".format(config))
    chip.writeRegister(0x02, 0x04)                                        // write raw register, (reg, value) → void
                                                                          // POLARITY: invert P2

    chip.watchChanges({ int changed -> printf("changed=0x%X%n", changed) } as IntConsumer) // start polling for changes, (callback[, intervalS]) → void
                                                                          // no INT pin exists; a 5 ms thread polls INPUT
    chip.pin(2).watch({ pin -> println('P2 changed') } as Consumer)       // watch P2, (handler[, trigger]) → void
                                                                          // edge filtered in software; all pins share one poll
    int changed = chip.pollChanges()                                    // read INPUT, get changed mask, () → int
                                                                          // XOR with the last INPUT value
    println("changed=0x%X".format(changed))
    chip.pin(2).unwatch()                                                 // stop watching, () → void
                                                                          // drops the handler
    chip.unwatchChanges()                                                   // stop polling, () → void
                                                                          // stops the polling thread
    chip.reset()                                                          // restore reset values, () → void
                                                                          // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF
} finally {
    connection.close()
}
