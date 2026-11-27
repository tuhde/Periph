///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-groovy:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Apds9250Minimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, Apds9250Minimal.DEFAULT_ADDRESS)                           // open I²C bus, device address, (bus, address=0x52) → I2CConnection
try {
    def sensor = new Apds9250Minimal(connection)                                                    // construct driver, (connection) → Apds9250Minimal
    for (int i = 0; i < 10; i++) {
        def c = sensor.read()                                                                       // Read the four channels, () → Counts {red, green, blue, ir}
        println("R ${c.red} G ${c.green} B ${c.blue} IR ${c.ir}")
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
