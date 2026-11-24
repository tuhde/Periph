///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-kotlin:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Apds9250Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, Apds9250Minimal.DEFAULT_ADDRESS).use { connection ->                        // open I²C bus, device address, (bus, address=0x52) → I2CConnection
        val sensor = Apds9250Minimal(connection)                                                    // construct driver, (connection) → Apds9250Minimal
        for (i in 0 until 10) {
            val c = sensor.read()                                                                   // Read the four channels, () → Counts {red, green, blue, ir}
            println("R ${c.red} G ${c.green} B ${c.blue} IR ${c.ir}")
            Thread.sleep(1000L)
        }
    }
}
