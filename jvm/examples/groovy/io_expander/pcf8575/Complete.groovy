///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.io_expander.Pcf8575Minimal
import it.uhde.periph.chips.io_expander.Pcf8575Full

def transport = new I2CTransport(1, 0x20)             // open I²C bus 1, device 0x20, (bus, address) → I2CTransport
try {
    def chip = new Pcf8575Full(transport)              // construct full driver, (transport) → Pcf8575Full

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

    chip.configureInterrupt { mask ->                  // start polling interrupt, (callback) → void
        println("changed: " + Integer.toBinaryString(mask))
    }

    def changed = chip.clearInterrupt()               // read and return 16-bit changed bitmask, () → int
    println("changed=0x" + Integer.toHexString(changed))

    chip.stopInterrupt()                              // stop polling thread, () → void
    println("v=$v state=$state")
} finally {
    transport.close()
}