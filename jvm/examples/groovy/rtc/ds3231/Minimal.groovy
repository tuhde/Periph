///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rtc.DS3231Minimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, DS3231Minimal.DEFAULT_ADDRESS)
try {
    def rtc = new DS3231Minimal(connection)                             // construct driver and confirm presence, (connection) → DS3231Minimal

    10.times {
        def dt = rtc.getDatetime()                                      // Read calendar clock, () → DateTime
        def tempC = rtc.readTemperature()                                // Read temperature, () → double C
        println(String.format("%04d-%02d-%02d (wd=%d) %02d:%02d:%02d  %.2f C",
                dt.year, dt.month, dt.day, dt.weekday, dt.hour, dt.minute, dt.second, tempC))
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
