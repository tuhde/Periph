///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.imu.MPU6050Minimal

def connection = new I2CConnection(1, 0x68)
try {
    def imu = new MPU6050Minimal(connection)                        // Create MPU6050 driver, (connection, addr=0x68) → None
    (0..<100).each { i ->
        double[] a = imu.accel()                                   // Read 3-axis acceleration, () → double[] m/s²
        double[] g = imu.gyro()                                    // Read 3-axis angular rate, () → double[] rad/s
        println "accel: ${String.format('%.2f', a[0])} ${String.format('%.2f', a[1])} ${String.format('%.2f', a[2])}  gyro: ${String.format('%.2f', g[0])} ${String.format('%.2f', g[1])} ${String.format('%.2f', g[2])}"
        Thread.sleep(100)
    }
} finally {
    connection.close()
}
