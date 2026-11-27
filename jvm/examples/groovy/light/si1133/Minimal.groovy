///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-groovy:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Si1133Minimal

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x55").replaceFirst("^0[xX]", ""), 16)

def connection = new I2CConnection(bus, addr)
try {
    def sensor = new Si1133Minimal(connection)                                                      // Create Si1133 driver, (connection) → Si1133Minimal
    sensor.init()                                                                                   // Check PART_ID, reset, UV + white channels, () → void

    while (true) {
        def m = sensor.read()                                                                       // Forced measurement, () → Reading {uv counts, white counts}
        System.out.printf("UV %d, white %d%n", m.uv(), m.white())
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
