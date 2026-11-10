///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rtc.PCF85063AMinimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, PCF85063AMinimal.DEFAULT_ADDRESS)  // open I²C bus, fixed device address, (bus, address) → I2CConnection
try {
    def rtc = new PCF85063AMinimal(connection)                           // construct driver, force 24-hour mode, (connection) → PCF85063AMinimal

    10.times {
        def dt = rtc.getDatetime()                                       // Read calendar clock, () → DateTime
        println(String.format("%04d-%02d-%02d %02d:%02d:%02d",
                dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second))
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
