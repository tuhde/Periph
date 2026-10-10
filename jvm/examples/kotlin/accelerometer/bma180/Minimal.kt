///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.Bma180Minimal

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.parseInt(it.replaceFirst("^0[xX]".toRegex(), ""), 16) } ?: 0x40
    I2CConnection(bus, addr).use { connection ->
        val accel = Bma180Minimal(connection)        // Create BMA150 driver, (connection)
        repeat(10) {
            val xyz = accel.read()                    // Read 3-axis acceleration, () → [g, g, g]
            println("x=%.3f y=%.3f z=%.3f g".format(xyz[0], xyz[1], xyz[2]))
            Thread.sleep(100)
        }
    }
}
