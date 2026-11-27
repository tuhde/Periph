///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.color.Isl29125Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, Isl29125Minimal.DEFAULT_ADDRESS).use { connection ->                        // open I²C bus, device address, (bus, address=0x44) → I2CConnection
        val sensor = Isl29125Minimal(connection)                                                    // construct driver, (connection) → Isl29125Minimal
        for (i in 0 until 10) {
            val c = sensor.read()                                                                   // Read the three channels, () → Counts {red, green, blue}
            println("R ${c.red} G ${c.green} B ${c.blue}")
            Thread.sleep(1000L)
        }
    }
}
