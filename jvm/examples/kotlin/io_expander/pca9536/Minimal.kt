///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.PCA9536Minimal

fun main() {
    I2CConnection(1, 0x41).use { connection ->                        // open I²C bus 1, device 0x41, (bus, address) → I2CConnection
        val chip = PCA9536Minimal(connection, 0x41)                      // construct driver, (connection, addr=0x41) → PCA9536Minimal

        var p2 = chip.pin(2)                                              // get pin proxy, (n) → Pin
        var p0 = chip.pin(0)                                              // get pin proxy, (n) → Pin
        p2.setInput()                                                     // set P2 as input, () → Unit
        p0.setOutput()                                                    // set P0 as output, () → Unit

        while (true) {
            val port = chip.readPort(0)                                   // read all 4 pins, (port=0) → Int bitmask
            val level = p2.read()                                         // read the actual pin level, () → Boolean
            if (level) p0.setHigh()                                       // drive P0 high, () → Unit
            else       p0.setLow()                                        // drive P0 low, () → Unit
            println("PORT=0x%X  P2=%b".format(port, level))
            Thread.sleep(200)
        }
    }
}
