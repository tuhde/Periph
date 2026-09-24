///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.temperature.TMP117Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, TMP117Minimal.DEFAULT_ADDRESS).use { connection ->                 // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val sensor = TMP117Minimal(connection)                                            // construct driver and check identity, (connection) → TMP117Minimal

        repeat(10) {
            val t = sensor.readTemperature()                                              // Read temperature, () → double °C
            println("%.4f °C".format(t))
            Thread.sleep(1000)
        }
    }
}
