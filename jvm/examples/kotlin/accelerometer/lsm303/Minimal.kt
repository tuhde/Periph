///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, 0x19).use { accelConn ->           // open accelerometer, (bus, address=0x19) → I2CConnection
        I2CConnection(bus, 0x1E).use { magConn ->         // open magnetometer, (bus, address=0x1E) → I2CConnection
            val sensor = LSM303Minimal(accelConn, magConn) // construct driver and run init, (accelConnection, magConnection) → LSM303Minimal

            repeat(10) {
                val a = sensor.readAcceleration()          // Read acceleration, () → DoubleArray g
                val m = sensor.readMagnetic()              // Read magnetic field, () → DoubleArray µT
                println("a=(%.3f, %.3f, %.3f) g  m=(%.1f, %.1f, %.1f) uT".format(a[0], a[1], a[2], m[0], m[1], m[2]))
                Thread.sleep(200)
            }
        }
    }
}
