///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.environmental.Aht21Minimal

fun main() {
    I2CConnection(1, 0x38).use { connection ->                 // open I²C bus 1, device 0x38, (bus, address) → I2CConnection
        val aht = Aht21Minimal(connection)                          // construct driver, (connection) → Aht21Minimal

        repeat(10) {
            val (t, h) = aht.read()    // trigger measurement, () → Pair<Double °C, Double %RH>
            println("T=%.2f °C  H=%.2f %%RH".format(t, h))
            Thread.sleep(1000)
        }
    }
}
