///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-kotlin:1.3.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.Mcp23008Full

fun main() {
    I2CConnection(1, 0x20).use { connection ->                        // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
        val chip = Mcp23008Full(connection, 0x20)                             // construct driver, (connection, addr=0x20) → Mcp23008Full
                                                                              // clears OLAT; all pins start as inputs

        val p0 = chip.pin(0)                                                  // get Full pin proxy, (n) → Pin
                                                                              // GP0; all eight pins are bidirectional
        p0.setOutput()                                                        // set direction to output, () → Unit
                                                                              // latches OLAT, then clears the IODIR bit
        p0.setHigh()                                                          // drive high, () → Unit
                                                                              // sets the OLAT bit via the shadow
        p0.toggle()                                                           // invert the output latch, () → Unit
                                                                              // one OLAT write, no read-modify-write
        val level = p0.read()                                                 // read the actual level, () → Boolean
                                                                              // reads GPIO
        println("GP0 level: %b".format(level))
        chip.pin(5).setInput()                                                // set GP5 as input, () → Unit
                                                                              // sets its IODIR bit
        chip.pin(5).setPullUp(true)                                           // enable the pull-up, (pullUp) → Unit
                                                                              // read-modify-write of GPPU via the shadow

        chip.writePort(0, 0x05)                                               // write the output latch, (port=0, mask) → Unit
                                                                              // whole-port OLAT write
        val port = chip.readPort(0)                                           // read all 8 pins, (port=0) → Int bitmask
                                                                              // GPIO register
        println("GPIO=0x%02X".format(port))

        chip.configurePullup(0xF0)                                            // pull-ups on GP4–GP7, (mask) → Unit
                                                                              // writes GPPU; only effective on inputs
        chip.configurePolarity(0x00)                                          // input polarity, (mask) → Unit
                                                                              // 1 bits invert the GPIO read
        chip.setDefaultValue(0x00)                                            // set DEFVAL, (mask) → Unit
                                                                              // reference for default-compare interrupts
        chip.configureIntPin(false, false)                                    // configure INT output, (openDrain, activeHigh) → Unit
                                                                              // push-pull, active-low (IOCON ODR/INTPOL)
        chip.setSequential(true)                                              // sequential access, (enabled) → Unit
                                                                              // IOCON SEQOP = 0 (driver default)
        val iodir = chip.readRegister(0x00)                                   // read raw register, (reg) → Int
                                                                              // escape hatch for debugging
        println("IODIR=0x%02X".format(iodir))
        chip.writeRegister(0x02, 0x10)                                        // write raw register, (reg, value) → Unit
                                                                              // GPINTEN: arm GP4

        chip.enablePinInterrupt(0xF0)                                         // arm interrupt-on-change, (mask) → Unit
                                                                              // GP4–GP7
        chip.onInterrupt({ status -> println("interrupt, INTF=0x%02X".format(status)) })// subscribe to INT, (callback[, intPin, compareToDefault]) → Unit
                                                                              // INT line if wired, else 5 ms polling
        chip.pin(4).watch({ println("GP4 changed") })                         // watch GP4, (handler[, trigger]) → Unit
                                                                              // arms GP4 in GPINTEN, edge filtered in software
        val flags = chip.pollInterrupt()                                      // read INTF and clear, () → Int
                                                                              // INTCAP is read and discarded
        val cap = chip.readCapture()                                          // read captured port, () → Int
                                                                              // port snapshot at the interrupt
        println("INTF=0x%02X INTCAP=0x%02X".format(flags, cap))
        chip.pin(4).unwatch()                                                 // stop watching, () → Unit
                                                                              // clears its GPINTEN bit
        chip.offInterrupt()                                                   // unsubscribe, () → Unit
                                                                              // stops delivery
        chip.reset()                                                          // restore reset values, () → Unit
                                                                              // rewrites all registers, clears shadows
    }
}
