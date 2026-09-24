///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.imu.MPU9250Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x68").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val imu = MPU9250Minimal(connection)                           // Create MPU9250 driver, (connection) → void

        while (true) {
            val a = imu.accel()                                       // Read 3-axis acceleration, () → DoubleArray m/s²
            val g = imu.gyro()                                        // Read 3-axis angular rate, () → DoubleArray rad/s
            println("accel: %.2f %.2f %.2f  gyro: %.2f %.2f %.2f".format(a[0], a[1], a[2], g[0], g[1], g[2]))
            Thread.sleep(100)
        }
    }
}