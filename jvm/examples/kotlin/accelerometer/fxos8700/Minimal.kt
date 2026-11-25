///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.FXOS8700Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1
    I2CConnection(bus, FXOS8700Minimal.I2C_ADDRESS).use { conn ->
        val compass = FXOS8700Minimal(conn)                       // Create FXOS8700 driver, (connection)
        repeat(10) {
            val accel = compass.readAccel()                       // Read acceleration, () → [x, y, z] g
            val mag = compass.readMag()                           // Read magnetic field, () → [x, y, z] µT
            println("accel x=%.4f y=%.4f z=%.4f g".format(accel[0], accel[1], accel[2]))
            println("mag   x=%.2f y=%.2f z=%.2f uT".format(mag[0], mag[1], mag[2]))
            Thread.sleep(1000)
        }
    }
}
