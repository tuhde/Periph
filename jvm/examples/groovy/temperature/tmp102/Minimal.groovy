///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.temperature.TMP102Minimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, TMP102Minimal.DEFAULT_ADDRESS)                   // open I²C bus, device address, (bus, address=0x48) → I2CConnection
try {
    def sensor = new TMP102Minimal(connection)                                           // construct driver (no bus access), (connection) → TMP102Minimal

    10.times {
        def t = sensor.readTemperature()                                                      // Read temperature, () → double °C
        printf("%.4f °C%n", t)
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
