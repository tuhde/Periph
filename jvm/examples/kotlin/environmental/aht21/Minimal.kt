///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.environmental.Aht21Minimal

fun main() {
    I2CTransport(1, 0x38).use { transport ->                 // open I²C bus 1, device 0x38, (bus, address) → I2CTransport
        val aht = Aht21Minimal(transport)                          // construct driver, (transport) → Aht21Minimal

        repeat(10) {
            val (t, h) = aht.read()    // trigger measurement, () → Pair<Double °C, Double %RH>
            println("T=%.2f °C  H=%.2f %%RH".format(t, h))
            Thread.sleep(1000)
        }
    }
}
