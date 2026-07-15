///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.light.Apds9960Minimal

def transport = new I2CTransport(1, 0x39)               // open I²C bus 1, device 0x39, (bus, address) → I2CTransport
try {
    def apds = new Apds9960Minimal(transport)                  // construct driver, (transport) → Apds9960Minimal

    10.times {
        int[] rgbc = apds.color()       // read all RGBC channels, () → int[] [clear, red, green, blue]
        printf("C=%d R=%d G=%d B=%d%n", rgbc[0], rgbc[1], rgbc[2], rgbc[3])
        Thread.sleep(1000)
    }
} finally {
    transport.close()
}
