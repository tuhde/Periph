///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.imu.Mpu6050Minimal

def transport = new I2CTransport(1, 0x68)
try {
    def imu = new Mpu6050Minimal(transport)                        // Create MPU6050 driver, (transport, addr=0x68) → None
    (0..<100).each { i ->
        double[] a = imu.accel()                                   // Read 3-axis acceleration, () → double[] m/s²
        double[] g = imu.gyro()                                    // Read 3-axis angular rate, () → double[] rad/s
        println "accel: ${String.format('%.2f', a[0])} ${String.format('%.2f', a[1])} ${String.format('%.2f', a[2])}  gyro: ${String.format('%.2f', g[0])} ${String.format('%.2f', g[1])} ${String.format('%.2f', g[2])}"
        Thread.sleep(100)
    }
} finally {
    transport.close()
}
