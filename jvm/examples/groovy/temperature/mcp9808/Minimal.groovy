///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.temperature.MCP9808Minimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, MCP9808Minimal.DEFAULT_ADDRESS)                   // open I²C bus, device address, (bus, address=0x18) → I2CConnection
try {
    def sensor = new MCP9808Minimal(connection)                                           // construct driver and check identity, (connection) → MCP9808Minimal

    10.times {
        def t = sensor.readTemperature()                                                  // Read ambient temperature, () → double °C
        printf("%.4f °C%n", t)
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
