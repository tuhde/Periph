///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Lps22dfMinimal

fun main() {
    I2CConnection(1, 0x5C).use { connection ->             // open I²C bus 1, device 0x5C, (bus, address=0x5C) → I2CConnection
        val sensor = Lps22dfMinimal(connection)                   // construct driver, verifies chip ID and applies defaults, (connection) → Lps22dfMinimal
        repeat(5) {
            val p = sensor.pressure()                              // read pressure, () → Double Pa
            val t = sensor.temperature()                           // read temperature, () → Double °C
            println("%.2f °C, %.0f Pa".format(t, p))
            Thread.sleep(1000)
        }
    }
}