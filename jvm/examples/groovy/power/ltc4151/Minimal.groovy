///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-groovy:1.5.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ltc4151Minimal

def connection = new I2CConnection(1, 0x6A)                 // open I²C bus 1, device 0x6A, (bus, address) → I2CConnection
try {
    def ltc = new Ltc4151Minimal(connection, 0.02d)         // construct driver, (connection, rSense=0.02 Ω) → Ltc4151Minimal

    10.times {
        double v = ltc.voltage()                            // read supply voltage, () → double V
        double c = ltc.current()                            // read load current, () → double A
        double p = ltc.power()                              // read load power, () → double W
        printf("V=%.3f V  I=%.4f A  P=%.3f W%n", v, c, p)
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
