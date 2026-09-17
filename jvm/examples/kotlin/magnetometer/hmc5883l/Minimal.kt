///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.magnetometer.Hmc5883lMinimal

fun main() {
    I2CConnection(1, 0x1E).use { connection ->
        val hmc5883l = Hmc5883lMinimal(connection)

        repeat(10) {
            val (x, y, z) = hmc5883l.magneticField()
            println("X=${x?.let { String.format("%.6f", it) } ?: "NaN"} T  " +
                    "Y=${y?.let { String.format("%.6f", it) } ?: "NaN"} T  " +
                    "Z=${z?.let { String.format("%.6f", it) } ?: "NaN"} T")
            Thread.sleep(1000)
        }
    }
}