///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.Pcf8575Minimal
import it.uhde.periph.chips.io_expander.Pcf8575Full

def connection = new I2CConnection(1, 0x20)           // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
try {
    def chip = new Pcf8575Full(connection)             // construct full driver, (connection) → Pcf8575Full

    def p0 = chip.pin(0)                               // get pin proxy, (n=0) → Pin
    p0.setOutput()                                     // set output mode, () → void
    p0.setHigh()                                       // set high (release to quasi-input), () → void
    p0.setLow()                                        // drive low, () → void
    def v = p0.read()                                  // read actual level, () → boolean

    chip.writePort(0, 0b00001111)                      // write Port 0, (port=0, mask) → void
    chip.writePort(1, 0b00001111)                      // write Port 1, (port=1, mask) → void

    def p8 = chip.pin(8)                              // get pin proxy, (n=8) → Pin
    p8.setInput()                                     // set input mode, () → void
    def state = p8.read()                             // read actual level, () → boolean

    chip.onInterrupt { mask ->                         // subscribe to INT line, (callback, intPin=connection.intPin()) → void
        println("changed: " + Integer.toBinaryString(mask))
    }

    def changed = chip.pollInterrupt()                // read and return 16-bit changed bitmask, () → int
    println("changed=0x" + Integer.toHexString(changed))

    def p9 = chip.pin(9)                              // get pin proxy, (n=9) → Pin
    p9.setInput()
    p9.watch({ pin ->                                  // subscribe to pin edges, (handler, trigger=CHANGE) → void
        println("P9 changed")                          // called with this pin when its state matches the trigger
    })
    p9.unwatch()                                       // unsubscribe pin handler, () → void

    chip.offInterrupt()                               // unsubscribe and stop delivery, () → void
    println("v=$v state=$state")
} finally {
    connection.close()
}