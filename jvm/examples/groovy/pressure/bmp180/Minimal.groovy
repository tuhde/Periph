///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp180Minimal

def connection = new I2CConnection(1, 0x77)            // open I²C bus 1, device 0x77, (bus, address=0x77) → I2CConnection
try {
    def sensor = new Bmp180Minimal(connection)               // construct driver, verifies chip ID and loads calibration, (connection) → Bmp180Minimal

    while (true) {
        double t = sensor.temperature()                     // read temperature, () → double °C
        double p = sensor.pressure()                        // read pressure, () → double hPa
        printf("temperature=%.2f °C  pressure=%.2f hPa%n", t, p)
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
