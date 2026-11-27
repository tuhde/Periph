///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-kotlin:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Si1145Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst(Regex("^0[xX]"), "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Si1145Minimal(connection)                                                   // Create Si1145 driver, (connection) → Si1145Minimal
        sensor.init()                                                                            // Check PART_ID, HW_KEY, UV + ALS + PS1, () → Unit

        while (true) {
            val m = sensor.read()                                                                // Forced PS + ALS, () → Reading {proximity, uvIndex, visible, infrared}
            System.out.printf("prox %d  UV %.2f  vis %d  ir %d%n", m.proximity, m.uvIndex, m.visible, m.infrared)
            Thread.sleep(1000)
        }
    }
}
