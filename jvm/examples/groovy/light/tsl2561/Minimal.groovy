///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-groovy:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.TSL2561Minimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, TSL2561Minimal.DEFAULT_ADDRESS)                            // open I²C bus, device address, (bus, address=0x39) → I2CConnection
try {
    def sensor = new TSL2561Minimal(connection)                                                    // construct driver, (connection) → TSL2561Minimal
    for (int i = 0; i < 10; i++) {
        println(String.format("%.1f lx", sensor.readLux()))                                        // Read illuminance, () → double lux
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
