///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.tof.VL53L1XMinimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, VL53L1XMinimal.DEFAULT_ADDRESS).use { connection ->                // open I²C bus, device address, (bus, address=0x29) → I2CConnection
        val sensor = VL53L1XMinimal(connection)                                           // Create VL53L1X driver, (connection) → VL53L1XMinimal

        repeat(50) {
            val d = sensor.distance()                                                     // Measure distance, () → Int mm
            if (sensor.rangeValid()) {                                                    // Check last measurement, () → Boolean
                println("$d mm")
            } else {
                println("out of range")
            }
            Thread.sleep(200)
        }
    }
}
