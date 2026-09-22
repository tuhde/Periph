///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rtc.DS3231Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, DS3231Minimal.DEFAULT_ADDRESS).use { connection ->   // open I²C bus, fixed device address, (bus, address) → I2CConnection
        val rtc = DS3231Minimal(connection)                                // construct driver and confirm presence, (connection) → DS3231Minimal

        repeat(10) {
            val dt = rtc.getDatetime()                                     // Read calendar clock, () → DateTime
            val tempC = rtc.readTemperature()                              // Read temperature, () → Double C
            println("%04d-%02d-%02d (wd=%d) %02d:%02d:%02d  %.2f C".format(
                dt.year, dt.month, dt.day, dt.weekday, dt.hour, dt.minute, dt.second, tempC))
            Thread.sleep(1000)
        }
    }
}
