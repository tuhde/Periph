///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.environmental.Aht21Minimal

def transport = new I2CTransport(1, 0x38)               // open I²C bus 1, device 0x38, (bus, address) → I2CTransport
try {
    def aht = new Aht21Minimal(transport)                     // construct driver, (transport) → Aht21Minimal

    10.times {
        double[] r = aht.read()    // trigger measurement, () → double[] {temperature_c, humidity_pct}
        printf("T=%.2f °C  H=%.2f %%RH%n", r[0], r[1])
        Thread.sleep(1000)
    }
} finally {
    transport.close()
}
