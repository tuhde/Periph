///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.color.Isl29125Minimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, Isl29125Minimal.DEFAULT_ADDRESS)                           // open I²C bus, device address, (bus, address=0x44) → I2CConnection
try {
    def sensor = new Isl29125Minimal(connection)                                                    // construct driver, (connection) → Isl29125Minimal
    for (int i = 0; i < 10; i++) {
        def c = sensor.read()                                                                       // Read the three channels, () → Counts {red, green, blue}
        println("R ${c.red} G ${c.green} B ${c.blue}")
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
