///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp384Minimal

def connection = new I2CConnection(1, (byte) 0x76)        // open I²C bus 1, device 0x76, (bus, address=0x76) → I2CConnection
try {
    def sensor = new Bmp384Minimal(connection)              // construct driver, verifies chip ID and loads calibration, (connection) → Bmp384Minimal

    5.times {
        def t = sensor.temperature()                         // read temperature, () → double °C
        def p = sensor.pressure()                            // read pressure, () → double hPa
        printf("temperature=%.2f °C  pressure=%.2f hPa%n", t, p)
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
