///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    // The module presents two I²C slaves on the same bus: accelerometer 0x19
    // and magnetometer 0x1E — both connections are required.
    I2CConnection(bus, 0x19).use { accelConn ->
        I2CConnection(bus, 0x1E).use { magConn ->
            val compass = LSM303Minimal(accelConn, magConn)      // Create LSM303 driver, (accel_connection, mag_connection)
            for (i in 0 until 10) {
                val a = compass.readAcceleration()               // Read 3-axis acceleration, () → [g, g, g]
                                                                 // burst-reads 0x28–0x2D with auto-increment, 1 mg/LSB at ±2 g
                val m = compass.readMagnetic()                   // Read 3-axis magnetic field, () → [µT, µT, µT]
                                                                 // on-wire order X, Z, Y high-byte-first, reordered to X, Y, Z
                println("a=(%+.3f, %+.3f, %+.3f) g  m=(%+.1f, %+.1f, %+.1f) uT".format(
                    a[0], a[1], a[2], m[0], m[1], m[2]))
                Thread.sleep(67)
            }
        }
    }
}
