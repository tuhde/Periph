///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Lps28dfwMinimal

fun main() {
    val connection = I2CConnection(1, 0x5C)                            // open I²C bus 1, device 0x5C, (bus, address=0x5C) → I2CConnection
    connection.use {
        val sensor = Lps28dfwMinimal(it)                              // construct driver, verifies chip ID and applies defaults, (connection) → Lps28dfwMinimal
        while (true) {
            val t = sensor.readTemperature()                            // read temperature, () → double °C
            val p = sensor.readPressure()                               // read pressure, () → double hPa
            println("temperature=%.2f °C  pressure=%.2f hPa".format(t, p))
            Thread.sleep(1000)
        }
    }
}