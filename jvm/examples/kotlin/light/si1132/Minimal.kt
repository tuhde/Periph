///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Si1132Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Si1132Minimal(connection)                                                                // Create Si1132 driver, (connection) → void

        while (true) {
        val m = sensor.read()                                                                                 // Read UV index and ALS counts, () → double[] {UV index, visible counts, infrared counts}
        System.out.printf("UV %.2f, visible %.0f, infrared %.0f%n", m[0], m[1], m[2])
        Thread.sleep(1000)
        }
    }
}
