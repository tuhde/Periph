///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.imu.Mpu6050Minimal

fun main() {
    I2CConnection(1, 0x68).use { connection ->
        val imu = Mpu6050Minimal(connection)                        // Create MPU6050 driver, (connection, addr=0x68) → None
        repeat(100) {
            val a = imu.accel()                                    // Read 3-axis acceleration, () → DoubleArray m/s²
            val g = imu.gyro()                                     // Read 3-axis angular rate, () → DoubleArray rad/s
            println("accel: %.2f %.2f %.2f  gyro: %.2f %.2f %.2f".format(
                a[0], a[1], a[2], g[0], g[1], g[2]))
            Thread.sleep(100)
        }
    }
}
