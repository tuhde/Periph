///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gyroscope.L3gd20hFull
import kotlin.math.sqrt

fun main() {
    val bus = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { it.replaceFirst("^0[xX]", "").toIntOrNull(16) } ?: 0x6A

    I2CConnection(bus, addr).use { conn ->
        val gyro = L3gd20hFull(conn)

        // --- Configure for shake detection at 190 Hz, ±500 dps ---
        gyro.configure(L3gd20hFull.ODR_190_HZ, 0, L3gd20hFull.FS_500_DPS)

        println("L3GD20H shake detector running. Shake the device...")

        while (true) {
            if (gyro.dataReady()) {                       // Check data ready
                val xyz = gyro.gyro()                     // Read angular rate
                val magnitude = sqrt(xyz[0]*xyz[0] + xyz[1]*xyz[1] + xyz[2]*xyz[2])
                if (magnitude > 1.0) {
                    println("SHAKE DETECTED: mag=${"%.3f".format(magnitude)} (x=${"%.3f".format(xyz[0])} y=${"%.3f".format(xyz[1])} z=${"%.3f".format(xyz[2])})")
                } else {
                    println("x=${"%.3f".format(xyz[0])} y=${"%.3f".format(xyz[1])} z=${"%.3f".format(xyz[2])} mag=${"%.3f".format(magnitude)}")
                }
            }
        }
    }
}