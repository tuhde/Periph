///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.temperature.TMP102Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, TMP102Minimal.DEFAULT_ADDRESS).use { connection ->                // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val sensor = TMP102Minimal(connection)                                           // construct driver (no bus access), (connection) → TMP102Minimal

        repeat(10) {
            val t = sensor.readTemperature()                                                      // Read temperature, () → double °C
            println("%.4f °C".format(t))
            Thread.sleep(1000)
        }
    }
}
