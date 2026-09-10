///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp581Minimal

def connection = new I2CConnection(1, 0x46)                      // open I²C bus 1, device 0x46, (bus, address=0x46) → I2CConnection
def sensor = new Bmp581Minimal(connection)                          // construct driver, verifies chip ID, (connection) → Bmp581Minimal
try {
    while (true) {
        def t = sensor.temperature()                                 // read temperature, () → double °C
        def p = sensor.pressure()                                    // read pressure, () → double Pa
        printf("temperature=%.2f °C  pressure=%.1f Pa%n", t, p)
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}