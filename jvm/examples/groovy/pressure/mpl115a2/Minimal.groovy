///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.MPL115A2Minimal

def connection = new I2CConnection(1, 0x60)            // open I²C bus 1, device 0x60, (bus, address=0x60) → I2CConnection
try {
    def sensor = new MPL115A2Minimal(connection)       // construct driver, loads the coefficients, (connection) → MPL115A2Minimal

    while (true) {
        double p = sensor.readPressure()               // read pressure, () → double Pa
        double t = sensor.readTemperature()            // read temperature, () → double °C (approximate)
        printf("pressure=%.0f Pa  temperature=%.1f °C%n", p, t)
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
