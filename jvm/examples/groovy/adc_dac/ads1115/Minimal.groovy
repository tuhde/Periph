///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-groovy:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS1115Minimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, ADS1115Minimal.DEFAULT_ADDRESS)                    // open I²C bus, device address, (bus, address=0x48) → I2CConnection
try {
        def adc = new ADS1115Minimal(connection)                                            // construct driver, (connection) → ADS1115Minimal

        10.times {
            def v = adc.readVoltage(0)                                                      // Read voltage, (channel=0) → double V
            printf("%.5f V%n", v)
            Thread.sleep(1000)
        }
} finally {
    connection.close()
}
