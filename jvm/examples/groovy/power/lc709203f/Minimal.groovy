///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-groovy:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.LC709203FMinimal

def connection = new I2CConnection(1, 0x0B)   // open I²C bus 1, device 0x0B, (bus, address) → I2CConnection
try {
    def gauge = new LC709203FMinimal(connection, 1000, 1)   // construct driver, (connection, capacityMah, profile) → LC709203FMinimal

    10.times {
        double v = gauge.voltage()     // read cell voltage, () → double V
        double p = gauge.percent()     // read state of charge, () → double %
        double t = gauge.temperature() // read cell temperature, () → double °C
        printf("%.3f V  %.1f %%  %.2f C%n", v, p, t)
        Thread.sleep(5000)
    }
} finally {
    connection.close()
}
