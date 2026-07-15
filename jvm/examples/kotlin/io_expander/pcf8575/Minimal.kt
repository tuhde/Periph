///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.io_expander.Pcf8575Minimal

fun main() {
    I2CTransport(1, 0x20).use { transport ->                          // open I²C bus 1, device 0x20, (bus, address) → I2CTransport
        val chip = Pcf8575Minimal(transport)                          // construct driver, (transport) → Pcf8575Minimal

        val p0 = chip.pin(0)                                          // get pin proxy, (n=0) → Pin
        p0.setOutput()                                                // set output mode — drives P00 low, () → Unit

        val p8 = chip.pin(8)                                          // get pin proxy, (n=8) → Pin
        p8.setInput()                                                 // set input mode — release P10 to quasi-input, () → Unit

        while (true) {
            val port0 = chip.readPort(0)                              // read Port 0, (port=0) → Int bitmask
            val port1 = chip.readPort(1)                              // read Port 1, (port=1) → Int bitmask
            if ((port1 and 0x01) == 0) p0.setLow()                    // drive P00 low (LED on), () → Unit
            else                       p0.setHigh()                    // release P00 high (LED off), () → Unit
            Thread.sleep(200)
        }
    }
}