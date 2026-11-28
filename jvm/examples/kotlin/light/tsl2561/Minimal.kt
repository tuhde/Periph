///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.TSL2561Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, TSL2561Minimal.DEFAULT_ADDRESS).use { connection ->                         // open I²C bus, device address, (bus, address=0x39) → I2CConnection
        val sensor = TSL2561Minimal(connection)                                                    // construct driver, (connection) → TSL2561Minimal
        for (i in 0 until 10) {
            println("%.1f lx".format(sensor.readLux()))                                            // Read illuminance, () → double lux
            Thread.sleep(1000)
        }
    }
}
