///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Si1133Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x55").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Si1133Minimal(connection)                                                      // Create Si1133 driver, (connection) → Si1133Minimal
        sensor.init()                                                                               // Check PART_ID, reset, UV + white channels, () → Unit

        while (true) {
            val m = sensor.read()                                                                   // Forced measurement, () → Reading {uv counts, white counts}
            println("UV ${m.uv}, white ${m.white}")
            Thread.sleep(1000)
        }
    }
}
