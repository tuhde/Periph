///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Max17048Minimal

def connection = new I2CConnection(1, 0x36)   // open I²C bus 1, device 0x36, (bus, address) → I2CConnection
try {
    def gauge = new Max17048Minimal(connection, 1)          // construct driver, (connection, cells) → Max17048Minimal

    10.times {
        printf("%.3f V  %.1f %%%n", gauge.voltage(), gauge.percent())// read cell voltage and state of charge, () → double V, double %
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
