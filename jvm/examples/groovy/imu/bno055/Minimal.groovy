///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.imu.BNO055Minimal

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x29").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def imu = new BNO055Minimal(conn)                                                       // Create BNO055 driver, (connection) → void

    while (true) {
        def e = imu.euler()                                                                 // Read fused Euler angles, () → double[] rad (heading, roll, pitch)
        printf("heading %.2f rad, roll %.2f, pitch %.2f%n", e[0], e[1], e[2])
        Thread.sleep(100)
    }
} finally {
    conn.close()
}
