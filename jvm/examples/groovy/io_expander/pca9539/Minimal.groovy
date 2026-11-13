///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.PCA9539Minimal

def connection = new I2CConnection(1, 0x74)           // open I²C bus 1, device 0x74, (bus, address) → I2CConnection
try {
    def chip = new PCA9539Minimal(connection, 0x74)                      // construct driver, (connection, addr=0x74) → PCA9539Minimal

    var p0 = chip.pin(0)                                                  // get pin proxy, (n) → Pin
    var p15 = chip.pin(15)                                                  // get pin proxy, (n) → Pin
    p0.setInput()                                                         // set P00 as input, () → void
    p15.setOutput()                                                        // set P17 as output, () → void

    while (true) {
        int port = chip.readPort(1)                                       // read the 8 pins of port 1, (port) → int bitmask
        boolean level = p0.read()                                         // read the actual pin level, () → boolean
        if (level) p15.setHigh()                                           // drive P17 high, () → void
        else       p15.setLow()                                            // drive P17 low, () → void
        println("PORT1=0x%02X  P0=%b".format(port, level))
        Thread.sleep(200)
    }
} finally {
    connection.close()
}
