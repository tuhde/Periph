///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.light.Apds9960Minimal

fun main() {
    I2CTransport(1, 0x39).use { transport ->                 // open I²C bus 1, device 0x39, (bus, address) → I2CTransport
        val apds = Apds9960Minimal(transport)                      // construct driver, (transport) → Apds9960Minimal

        repeat(10) {
            val rgbc = apds.color()        // read all RGBC channels, () → IntArray [clear, red, green, blue]
            println("C=${rgbc[0]} R=${rgbc[1]} G=${rgbc[2]} B=${rgbc[3]}")
            Thread.sleep(1000)
        }
    }
}
