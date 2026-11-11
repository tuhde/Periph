///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.PCA6416AMinimal

fun main() {
    I2CConnection(1, 0x20).use { connection ->                        // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
        val chip = PCA6416AMinimal(connection, 0x20)                      // construct driver, (connection, addr=0x20) → PCA6416AMinimal

        var p0 = chip.pin(0)                                              // get pin proxy, (n) → Pin
        var p15 = chip.pin(15)                                              // get pin proxy, (n) → Pin
        p0.setInput()                                                     // set P0_0 as input, () → Unit
        p15.setOutput()                                                    // set P1_7 as output, () → Unit

        while (true) {
            val port = chip.readPort(1)                                   // read the 8 pins of port 1, (port) → Int bitmask
            val level = p0.read()                                         // read the actual pin level, () → Boolean
            if (level) p15.setHigh()                                       // drive P1_7 high, () → Unit
            else       p15.setLow()                                        // drive P1_7 low, () → Unit
            println("PORT1=0x%02X  P0=%b".format(port, level))
            Thread.sleep(200)
        }
    }
}
