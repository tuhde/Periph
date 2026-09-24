///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rtc.PCF8523Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, PCF8523Minimal.DEFAULT_ADDRESS).use { connection ->  // open I²C bus, fixed device address, (bus, address) → I2CConnection
        val rtc = PCF8523Minimal(connection)                               // construct driver, enable battery backup, (connection) → PCF8523Minimal

        repeat(10) {
            val dt = rtc.getDatetime()                                     // Read calendar clock, () → DateTime
            println("%04d-%02d-%02d %02d:%02d:%02d".format(dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second))
            Thread.sleep(1000)
        }
    }
}
