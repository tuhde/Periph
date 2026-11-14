///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.SHT31Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x44").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = SHT31Minimal(connection)                                               // Create SHT31 driver, (connection) → void

        while (true) {
            val v = sensor.read()                                                           // Read temperature and humidity, () → double[] {°C, %RH}
            println("%.2f C, %.1f %%RH".format(v[0], v[1]))
            Thread.sleep(2000)
        }
    }
}
