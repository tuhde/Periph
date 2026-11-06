///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-groovy:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS1013Minimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, ADS1013Minimal.DEFAULT_ADDRESS)   // open I²C bus, device address, (bus, address=0x48) → I2CConnection
try {
    def adc = new ADS1013Minimal(connection)                                            // construct driver, (connection) → ADS1013Minimal

    10.times {
        def raw = adc.readRaw()                                                         // Read raw code, () → int (−2048…2047)
        def v = adc.readVoltage()                                                       // Read voltage, () → double V
        printf("%d -> %.3f V%n", raw, v)
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
