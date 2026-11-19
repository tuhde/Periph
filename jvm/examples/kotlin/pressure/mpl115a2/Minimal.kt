///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-kotlin:1.5.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.MPL115A2Minimal

fun main() {
    I2CConnection(1, 0x60).use { connection ->             // open I²C bus 1, device 0x60, (bus, address=0x60) → I2CConnection
        val sensor = MPL115A2Minimal(connection)           // construct driver, loads the coefficients, (connection) → MPL115A2Minimal

        while (true) {
            val p = sensor.readPressure()                  // read pressure, () → Double Pa
            val t = sensor.readTemperature()               // read temperature, () → Double °C (approximate)
            println("pressure=%.0f Pa  temperature=%.1f °C".format(p, t))
            Thread.sleep(1000)
        }
    }
}
