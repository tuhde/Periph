///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.environmental.Aht21Minimal

def connection = new I2CConnection(1, 0x38)               // open I²C bus 1, device 0x38, (bus, address) → I2CConnection
try {
    def aht = new Aht21Minimal(connection)                     // construct driver, (connection) → Aht21Minimal

    10.times {
        double[] r = aht.read()    // trigger measurement, () → double[] {temperature_c, humidity_pct}
        printf("T=%.2f °C  H=%.2f %%RH%n", r[0], r[1])
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
