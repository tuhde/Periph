///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1
    I2CConnection(bus, LSM303Minimal.I2C_ADDRESS_ACCEL).use { accelConn ->
        I2CConnection(bus, LSM303Minimal.I2C_ADDRESS_MAG).use { magConn ->
            val compass = LSM303Minimal(accelConn, magConn)   // Create LSM303 driver, (accel_connection, mag_connection)
            repeat(10) {
                val accel = compass.readAcceleration()        // Read acceleration, () → [g, g, g]
                val mag = compass.readMagnetic()               // Read magnetic field, () → [µT, µT, µT]
                println("accel x=%.4f y=%.4f z=%.4f g".format(accel[0], accel[1], accel[2]))
                println("mag   x=%.2f y=%.2f z=%.2f uT".format(mag[0], mag[1], mag[2]))
                Thread.sleep(1000)
            }
        }
    }
}
