///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gyroscope.L3gd20hMinimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { it.replaceFirst("^0[xX]", "").toIntOrNull(16) } ?: 0x6A

    I2CConnection(bus, addr).use { conn ->
        val gyro = L3gd20hMinimal(conn)

        while (true) {
            val xyz = gyro.gyro()  // Read angular rate, () -> FloatArray rad/s
            println("x=${xyz[0]:.3f} y=${xyz[1]:.3f} z=${xyz[2]:.3f} rad/s")
            Thread.sleep(100)
        }
    }
}