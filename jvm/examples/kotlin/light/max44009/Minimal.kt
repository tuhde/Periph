///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.MAX44009Minimal
import kotlin.math.log10

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, MAX44009Minimal.DEFAULT_ADDRESS).use { connection ->                        // open I²C bus, device address, (bus, address=0x4A) → I2CConnection
        val sensor = MAX44009Minimal(connection)                                                   // construct driver, (connection) → MAX44009Minimal
        for (i in 0 until 10) {
            println("${sensor.lux()} lx")                                                          // Read illuminance, () → double lux
            Thread.sleep(1000)
        }
    }
}
