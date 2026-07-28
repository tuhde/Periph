///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp180Minimal

fun main() {
    I2CConnection(1, 0x77).use { connection ->             // open I²C bus 1, device 0x77, (bus, address=0x77) → I2CConnection
        val sensor = Bmp180Minimal(connection)                   // construct driver, verifies chip ID and loads calibration, (connection) → Bmp180Minimal

        while (true) {
            val t = sensor.temperature()                        // read temperature, () → Double °C
            val p = sensor.pressure()                           // read pressure, () → Double hPa
            println("temperature=%.2f °C  pressure=%.2f hPa".format(t, p))
            Thread.sleep(1000)
        }
    }

}
