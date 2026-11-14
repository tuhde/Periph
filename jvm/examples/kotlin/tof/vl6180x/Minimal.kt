///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.tof.VL6180XMinimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, VL6180XMinimal.DEFAULT_ADDRESS, null, null, 2).use { connection ->  // open I²C bus, device address, register width 2, (bus, address=0x29, null, null, regBytes=2) → I2CConnection
        val sensor = VL6180XMinimal(connection)                                              // Create VL6180X driver, (connection) → VL6180XMinimal
        repeat(50) {
            val mm = sensor.readRange()                                               // Single-shot range, () → int mm
            println("mm" + " " + mm)
            Thread.sleep(200)
        }
    }
}
