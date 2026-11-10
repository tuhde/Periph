///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.Mcp23008Full
import java.util.function.IntConsumer
import java.util.function.Consumer

def connection = new I2CConnection(1, 0x20)           // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
try {
    def chip = new Mcp23008Full(connection, 0x20)                         // construct driver, (connection, addr=0x20) → Mcp23008Full
                                                                          // clears OLAT; all pins start as inputs

    def p0 = chip.pin(0)                                                  // get Full pin proxy, (n) → Pin
                                                                          // GP0; all eight pins are bidirectional
    p0.setOutput()                                                        // set direction to output, () → void
                                                                          // latches OLAT, then clears the IODIR bit
    p0.setHigh()                                                          // drive high, () → void
                                                                          // sets the OLAT bit via the shadow
    p0.toggle()                                                           // invert the output latch, () → void
                                                                          // one OLAT write, no read-modify-write
    boolean level = p0.read()                                             // read the actual level, () → boolean
                                                                          // reads GPIO
    printf("GP0 level: %b%n", level)
    chip.pin(5).setInput()                                                // set GP5 as input, () → void
                                                                          // sets its IODIR bit
    chip.pin(5).setPullUp(true)                                           // enable the pull-up, (pullUp) → void
                                                                          // read-modify-write of GPPU via the shadow

    chip.writePort(0, 0x05)                                               // write the output latch, (port=0, mask) → void
                                                                          // whole-port OLAT write
    int port = chip.readPort(0)                                           // read all 8 pins, (port=0) → int bitmask
                                                                          // GPIO register
    printf("GPIO=0x%02X%n", port)

    chip.configurePullup(0xF0)                                            // pull-ups on GP4–GP7, (mask) → void
                                                                          // writes GPPU; only effective on inputs
    chip.configurePolarity(0x00)                                          // input polarity, (mask) → void
                                                                          // 1 bits invert the GPIO read
    chip.setDefaultValue(0x00)                                            // set DEFVAL, (mask) → void
                                                                          // reference for default-compare interrupts
    chip.configureIntPin(false, false)                                    // configure INT output, (openDrain, activeHigh) → void
                                                                          // push-pull, active-low (IOCON ODR/INTPOL)
    chip.setSequential(true)                                              // sequential access, (enabled) → void
                                                                          // IOCON SEQOP = 0 (driver default)
    int iodir = chip.readRegister(0x00)                                   // read raw register, (reg) → int
                                                                          // escape hatch for debugging
    printf("IODIR=0x%02X%n", iodir)
    chip.writeRegister(0x02, 0x10)                                        // write raw register, (reg, value) → void
                                                                          // GPINTEN: arm GP4

    chip.enablePinInterrupt(0xF0)                                         // arm interrupt-on-change, (mask) → void
                                                                          // GP4–GP7
    chip.onInterrupt({ int status -> printf("interrupt, INTF=0x%02X%n", status) } as IntConsumer)// subscribe to INT, (callback[, intPin, compareToDefault]) → void
                                                                          // INT line if wired, else 5 ms polling
    chip.pin(4).watch({ p -> println("GP4 changed") } as Consumer)        // watch GP4, (handler[, trigger]) → void
                                                                          // arms GP4 in GPINTEN, edge filtered in software
    int flags = chip.pollInterrupt()                                      // read INTF and clear, () → int
                                                                          // INTCAP is read and discarded
    int cap = chip.readCapture()                                          // read captured port, () → int
                                                                          // port snapshot at the interrupt
    printf("INTF=0x%02X INTCAP=0x%02X%n", flags, cap)
    chip.pin(4).unwatch()                                                 // stop watching, () → void
                                                                          // clears its GPINTEN bit
    chip.offInterrupt()                                                   // unsubscribe, () → void
                                                                          // stops delivery
    chip.reset()                                                          // restore reset values, () → void
                                                                          // rewrites all registers, clears shadows
} finally {
    connection.close()
}
