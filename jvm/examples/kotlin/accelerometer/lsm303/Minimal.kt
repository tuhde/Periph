///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Minimal

fun main() {
    val bus = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    I2CConnection(bus, 0x19).use { accelConnection ->
    I2CConnection(bus, 0x1E).use { magConnection ->
        val chip = LSM303Minimal(accelConnection, magConnection)   // Create LSM303 driver, (accelConnection, magConnection) → void
        for (i in 0 until 10) {
            val a = chip.readAcceleration()                        // Read 3-axis acceleration, () → DoubleArray g
            val m = chip.readMagnetic()                            // Read 3-axis magnetic field, () → DoubleArray µT
            println("a=(%.3f, %.3f, %.3f) g  m=(%.1f, %.1f, %.1f) µT".format(a[0], a[1], a[2], m[0], m[1], m[2]))
            Thread.sleep(100)
        }
    }
    }
}
