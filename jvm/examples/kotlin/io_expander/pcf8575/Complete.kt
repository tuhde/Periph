///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.io_expander.Pcf8575Minimal
import it.uhde.periph.chips.io_expander.Pcf8575Full

fun main() {
    I2CTransport(1, 0x20).use { transport ->                          // open I²C bus 1, device 0x20, (bus, address) → I2CTransport
        val chip = Pcf8575Full(transport)                             // construct full driver, (transport) → Pcf8575Full

        val p0 = chip.pin(0)                                         // get pin proxy, (n=0) → Pin
        p0.setOutput()                                               // set output mode, () → Unit
        p0.setHigh()                                                  // set high (release to quasi-input), () → Unit
        p0.setLow()                                                   // drive low, () → Unit
        val v = p0.read()                                             // read actual level, () → Boolean

        chip.writePort(0, 0b00001111)                                 // write Port 0, (port=0, mask) → Unit
        chip.writePort(1, 0b00001111)                                 // write Port 1, (port=1, mask) → Unit

        val p8 = chip.pin(8)                                         // get pin proxy, (n=8) → Pin
        p8.setInput()                                                 // set input mode, () → Unit
        val state = p8.read()                                         // read actual level, () → Boolean

        chip.configureInterrupt { mask ->                             // start polling interrupt, (callback) → Unit
            println("changed: " + Integer.toBinaryString(mask))
        }

        val changed = chip.clearInterrupt()                          // read and return 16-bit changed bitmask, () → Int
        println("changed=0x" + Integer.toHexString(changed))

        chip.stopInterrupt()                                          // stop polling thread, () → Unit
        println("v=$v state=$state")
    }
}