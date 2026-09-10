///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Lps22dfMinimal

def connection = new I2CConnection(1, 0x5C)            // open I²C bus 1, device 0x5C, (bus, address=0x5C) → I2CConnection
try {
    def sensor = new Lps22dfMinimal(connection)               // construct driver, verifies chip ID and applies defaults, (connection) → Lps22dfMinimal
    5.times {
        double p = sensor.pressure()                          // read pressure, () → double Pa
        double t = sensor.temperature()                       // read temperature, () → double °C
        printf("%.2f °C, %.0f Pa%n", t, p)
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}