///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.MAX44009Minimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, MAX44009Minimal.DEFAULT_ADDRESS)                           // open I²C bus, device address, (bus, address=0x4A) → I2CConnection
try {
    def sensor = new MAX44009Minimal(connection)                                                   // construct driver, (connection) → MAX44009Minimal
    for (int i = 0; i < 10; i++) {
        println("${sensor.lux()} lx")                                                              // Read illuminance, () → double lux
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
