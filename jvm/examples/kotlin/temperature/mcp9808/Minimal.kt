///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.temperature.MCP9808Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, MCP9808Minimal.DEFAULT_ADDRESS).use { connection ->                // open I²C bus, device address, (bus, address=0x18) → I2CConnection
        val sensor = MCP9808Minimal(connection)                                           // construct driver and check identity, (connection) → MCP9808Minimal

        repeat(10) {
            val t = sensor.readTemperature()                                              // Read ambient temperature, () → double °C
            println("%.4f °C".format(t))
            Thread.sleep(1000)
        }
    }
}
