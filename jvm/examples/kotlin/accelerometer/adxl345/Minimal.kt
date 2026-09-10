///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.Adxl345Minimal

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.toInt(16) ?: 0x53

    I2CConnection(bus, addr).use { connection ->                       // open I²C bus, device address, (bus, address) → I2CConnection
        val sensor = Adxl345Minimal(connection)                       // construct driver and verify DEVID, (connection) → Adxl345Minimal

        repeat(10) {
            val xyz = sensor.read()                                    // Read 3-axis acceleration, () → DoubleArray g, g, g
            println("x=%.3f y=%.3f z=%.3f g".format(xyz[0], xyz[1], xyz[2]))
            Thread.sleep(100)
        }
    }
}