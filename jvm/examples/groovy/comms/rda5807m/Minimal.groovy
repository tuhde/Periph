///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.comms.Rda5807mMinimal

def transport = new I2CTransport(1, 0x10)                  // open I²C bus 1, device 0x10, (bus, address) → I2CTransport
try {
    def fm = new Rda5807mMinimal(transport, 100.0d, 8)      // construct driver, (transport, frequencyMhz=100.0, volume=8) → Rda5807mMinimal

    while (true) {
        Double freq = fm.seek(true)   // seek to next station, (up=true) → Double or null
        if (freq != null) {
            printf("%.2f MHz%n", freq)
        }
        Thread.sleep(3000)
    }
} finally {
    transport.close()
}
