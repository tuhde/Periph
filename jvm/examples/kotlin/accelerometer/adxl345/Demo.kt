///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.Adxl345Minimal
import kotlin.math.sqrt

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.toInt(16) ?: 0x53

    I2CConnection(bus, addr).use { connection ->
        val accel = Adxl345Minimal(connection)                       // construct driver and verify DEVID, (connection) → Adxl345Minimal

        // --- 50-sample stationary tilt characterization at 10 Hz ---
        // With the sensor flat and the Z axis up, gravity should project entirely
        // onto Z. Tilting the board visibly redistributes the 1 *g* magnitude
        // across X and Y; the total vector magnitude stays near 1 *g*.
        val samples = 50
        var magMin = Double.POSITIVE_INFINITY
        var magMax = Double.NEGATIVE_INFINITY

        for (n in 0 until samples) {
            val xyz = accel.read()                                   // Read 3-axis acceleration, () → DoubleArray g, g, g
            val mag = sqrt(xyz[0] * xyz[0] + xyz[1] * xyz[1] + xyz[2] * xyz[2])
            if (mag < magMin) magMin = mag
            if (mag > magMax) magMax = mag
            println("%2d  x=%+.3f  y=%+.3f  z=%+.3f  |a|=%.3f g".format(n, xyz[0], xyz[1], xyz[2], mag))
            Thread.sleep(100)
        }

        println("min |a|=%.3f g  max |a|=%.3f g".format(magMin, magMax))
    }
}