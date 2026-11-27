///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Mpl3115a2Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Mpl3115a2Minimal(connection)                                                   // Create MPL3115A2 driver, (connection) → Mpl3115a2Minimal
        sensor.init()                                                                               // Reset and apply defaults, () → void

        while (true) {
            val v = sensor.read()                                                                   // Read pressure and temperature, () → double[] {Pa, °C}
            System.out.printf("%.2f Pa, %.2f C%n", v[0], v[1])
            Thread.sleep(2000)
        }
    }
}
