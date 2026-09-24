///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp085Minimal

fun main() {
    I2CConnection(1, 0x77).use { connection ->
        val sensor = Bmp085Minimal(connection)

        while (true) {
            val t = sensor.temperature()
            val p = sensor.pressure()
            println("temperature=${t:.2f} °C  pressure=${p:.2f} Pa")
            Thread.sleep(1000)
        }
    }
}