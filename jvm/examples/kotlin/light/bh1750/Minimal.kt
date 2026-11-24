///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-kotlin:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Bh1750Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x23").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Bh1750Minimal(connection)                                                              // Create BH1750 driver, (connection) → Bh1750Minimal
        sensor.init()                                                                                       // Power on, () → void

        while (true) {
            val lux = sensor.read()                                                                         // Measure illuminance, () → double lx
            System.out.printf("%.1f lx%n", lux)
            Thread.sleep(1000L)
        }
    }
}
