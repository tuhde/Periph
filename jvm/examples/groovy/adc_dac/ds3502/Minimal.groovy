///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.DS3502Minimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, DS3502Minimal.DEFAULT_ADDRESS)                             // open I²C bus, device address, (bus, address=0x28) → I2CConnection
try {
    def pot = new DS3502Minimal(connection, 15.0d, 0.0d)                                           // construct driver, (connection, vHigh=1.0 V, vLow=0.0 V) → DS3502Minimal
    def codes = [0, 32, 64, 96, 127]
    for (int code : codes) {
        pot.setWiper(code)                                                                         // Set wiper position, (code 0–127) → void
        println("${code} ${pot.getWiper()}")                                                       // Read wiper position, () → int 0–127
        println(pot.getVoltage())                                                                  // Read unloaded RW voltage, () → double V
        Thread.sleep(500)
    }
    pot.setVoltage(6.0d)                                                                           // Set RW output voltage, (volts V) → void
} finally {
    connection.close()
}
