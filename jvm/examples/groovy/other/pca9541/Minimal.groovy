///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.other.PCA9541Minimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, PCA9541Minimal.DEFAULT_ADDRESS)                            // open I²C bus, device address, (bus, address=0x70) → I2CConnection
try {
    def selector = new PCA9541Minimal(connection)                                                  // construct driver, (connection) → PCA9541Minimal
    println("has control " + selector.hasControl())                                                // Report control, () → boolean
    println("acquire " + selector.acquire())                                                       // Take the downstream bus, (busInit=false, timeoutMs=20) → boolean
    Thread.sleep(1000)
    selector.release()                                                                             // Give the bus to the other master, () → void
    println("has control " + selector.hasControl())
} finally {
    connection.close()
}
