///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.imu.BNO055Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x29").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val imu = BNO055Minimal(connection)                                                 // Create BNO055 driver, (connection) → void

        while (true) {
            val e = imu.euler()                                                             // Read fused Euler angles, () → double[] rad (heading, roll, pitch)
            println("heading %.2f rad, roll %.2f, pitch %.2f".format(e[0], e[1], e[2]))
            Thread.sleep(100)
        }
    }
}
