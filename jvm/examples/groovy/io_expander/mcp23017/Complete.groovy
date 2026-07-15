///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.io_expander.Mcp23017Minimal
import it.uhde.periph.chips.io_expander.Mcp23017Full

def transport = new I2CTransport(1, 0x20)             // open I²C bus 1, device 0x20, (bus, address) → I2CTransport
try {
    def chip = new Mcp23017Full(transport, 0x20)       // construct full driver, (transport, addr=0x20) → Mcp23017Full

    def p0 = chip.pin(0)                              // get full pin proxy, (n) → Pin

    p0.setHigh()                                      // drive high, () → void
    p0.setLow()                                       // drive low, () → void

    boolean level = p0.read()                        // read actual level, () → boolean
    println "GPA0 level: $level"

    int porta = chip.readPort(0)                     // read all 8 pins, (port=0) → int bitmask
    int portb = chip.readPort(1)                      // read all 8 pins, (port=1) → int bitmask
    printf "PORTA=0x%02X  PORTB=0x%02X%n", porta, portb

    chip.writePort(0, 0b00001111)                    // write all 8 pins, (port, mask) → void
    chip.writePort(1, 0b11110000)                    // write all 8 pins, (port, mask) → void

    chip.configurePullup(1, 0b01111111)               // enable pull-ups, (port=1, mask) → void

    chip.configurePullup(0, 0x55)                     // enable pull-ups, (port=0, mask) → void

    chip.configurePolarity(0, 0x00)                  // configure polarity, (port=0, mask) → void

    int flags = chip.readInterruptFlags(0)            // read interrupt flags, (port=0) → int
    println "INT flags PORTA: 0x${Integer.toHexString(flags)}"

    int changed = chip.clearInterrupt(0)             // read and clear interrupt, (port=0) → int
    println "changed on init: 0x${Integer.toHexString(changed)}"

    def p1  = chip.pin(1)                            // get full pin proxy, (n) → Pin
    def p15 = chip.pin(15)                           // get full pin proxy, (n) → Pin

    chip.writePort(0, 0x80)                          // write all 8 pins, (port=0, mask=0x80) → void
    chip.writePort(1, 0x00)                          // write all 8 pins, (port=1, mask=0x00) → void

    int porta2 = chip.readPort(0)                    // read all 8 pins, (port=0) → int bitmask
    int portb2 = chip.readPort(1)                    // read all 8 pins, (port=1) → int bitmask
    printf "PORTA=0x%02X  PORTB=0x%02X%n", porta2, portb2

    println "All API methods exercised."
} finally {
    transport.close()
}