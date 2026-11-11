///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.PCA9536Minimal

def connection = new I2CConnection(1, 0x41)           // open I²C bus 1, device 0x41, (bus, address) → I2CConnection
try {
    def chip = new PCA9536Minimal(connection, 0x41)                      // construct driver, (connection, addr=0x41) → PCA9536Minimal

    var p2 = chip.pin(2)                                                  // get pin proxy, (n) → Pin
    var p0 = chip.pin(0)                                                  // get pin proxy, (n) → Pin
    p2.setInput()                                                         // set P2 as input, () → void
    p0.setOutput()                                                        // set P0 as output, () → void

    while (true) {
        int port = chip.readPort(0)                                       // read all 4 pins, (port=0) → int bitmask
        boolean level = p2.read()                                         // read the actual pin level, () → boolean
        if (level) p0.setHigh()                                           // drive P0 high, () → void
        else       p0.setLow()                                            // drive P0 low, () → void
        println("PORT=0x%X  P2=%b".format(port, level))
        Thread.sleep(200)
    }
} finally {
    connection.close()
}
