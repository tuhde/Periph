///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gyroscope.L3g4200dMinimal

fun main() {
    I2CConnection(1, 0x68).use { connection ->                  // open I²C bus 1, device 0x68, (bus, address=0x68) → I2CConnection
        val sensor = L3g4200dMinimal(connection)               // construct driver, verifies chip ID, (connection) → L3g4200dMinimal
        repeat(10) {
            val (x, y, z) = sensor.angularRate()                 // read X/Y/Z angular rate, () → Triple<Float, Float, Float> rad/s
            println("X=%.3f Y=%.3f Z=%.3f rad/s".format(x, y, z))
            Thread.sleep(100)
        }
    }
}
