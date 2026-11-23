///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-groovy:1.5.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.LM25066Minimal

def connection = new I2CConnection(1, 0x40)   // open I²C bus 1, device 0x40, (bus, address) → I2CConnection
try {
    def hotswap = new LM25066Minimal(connection, 1.0d, false)   // construct driver, (connection, rSenseMohm, currentLimitHigh) → LM25066Minimal

    10.times {
        println(hotswap.voltageIn())                                                   // read VIN, () → double V
        println(hotswap.voltageOut())                                                  // read VOUT, () → double V
        println(hotswap.current())                                                     // read input current, () → double A
        println(hotswap.power())                                                       // read input power, () → double W
        println(hotswap.temperature())                                                 // read MOSFET temperature, () → double °C
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
