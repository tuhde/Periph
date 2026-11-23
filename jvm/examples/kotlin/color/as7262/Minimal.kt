///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-kotlin:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.color.As7262Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x49").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = As7262Minimal(connection)                                                      // Create AS7262 driver, (connection) → void
        sensor.init()                                                                               // Reset and configure defaults, () → void

        while (true) {
            val ch = sensor.read()                                                                  // Read six calibrated channels, () → Channels
            println("V %.1f B %.1f G %.1f Y %.1f O %.1f R %.1f".format(ch.violet, ch.blue, ch.green, ch.yellow, ch.orange, ch.red))
            println("%.0f C".format(sensor.temperature()))
            Thread.sleep(2000)
        }
    }
}
