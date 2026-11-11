///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.CAT5171Minimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, CAT5171Minimal.DEFAULT_ADDRESS)                             // open I²C bus, device address, (bus, address=0x2C) → I2CConnection
try {
    def pot = new CAT5171Minimal(connection)                                                        // construct driver, (connection, rAb=100000.0 Ω, rWiper=50.0 Ω) → CAT5171Minimal
    def codes = [0, 64, 128, 192, 255]
    for (int code : codes) {
        pot.setWiper(code)                                                                          // Set wiper position, (code 0–255) → void
        println("${code} ${pot.getWiper()}")                                                        // Read wiper position, () → int 0–255
        println(pot.getResistance())                                                                // Read W–B resistance, () → double Ω
        Thread.sleep(500)
    }
    pot.setResistance(22000.0d)                                                                     // Set W–B resistance, (ohms Ω) → void
} finally {
    connection.close()
}
