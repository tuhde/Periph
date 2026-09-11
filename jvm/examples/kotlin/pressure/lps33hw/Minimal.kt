///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Lps33hwMinimal

fun main() {
    I2CConnection(1, 0x5C).use { connection ->             // open I²C bus 1, device 0x5C, (bus, address=0x5C) → I2CConnection
        val sensor = Lps33hwMinimal(connection)                   // construct driver, verifies chip ID, (connection) → Lps33hwMinimal

        while (true) {
            val t = sensor.temperature()                        // read temperature, () → Double °C
            val p = sensor.pressure()                           // read pressure, () → Double Pa
            println("temperature=%.2f °C  pressure=%.1f Pa".format(t, p))
            Thread.sleep(1000)
        }
    }
}