///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.imu.QMI8658AMinimal

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = System.getenv().getOrDefault("I2C_ADDR", "0x6B").replaceFirst("^0[xX]", "") as int

def conn = new I2CConnection(bus, addr)
try {
    def imu = new QMI8658AMinimal(conn)                           // Create QMI8658A driver, (connection) → void

    while (true) {
        def a = imu.accel() // Read 3-axis acceleration, () → double[] m/s²
        def g = imu.gyro() // Read 3-axis angular rate, () → double[] rad/s
        printf("accel: %.2f %.2f %.2f  gyro: %.2f %.2f %.2f%n", a[0], a[1], a[2], g[0], g[1], g[2])
        Thread.sleep(100)
    }
} finally {
    conn.close()
}
