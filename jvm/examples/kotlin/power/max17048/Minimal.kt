///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-kotlin:1.5.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Max17048Minimal

fun main() {
    I2CConnection(1, 0x36).use { connection ->   // open I²C bus 1, device 0x36, (bus, address) → I2CConnection
        val gauge = Max17048Minimal(connection, 1)          // construct driver, (connection, cells) → Max17048Minimal

        repeat(10) {
            println("%.3f V  %.1f %%".format(gauge.voltage(), gauge.percent()))// read cell voltage and state of charge, () → double V, double %
            Thread.sleep(1000)
        }
    }
}
