///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.Pcf8574Minimal

fun main() {
    I2CConnection(1, 0x20).use { connection ->                        // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
        val chip = Pcf8574Minimal(connection)                          // construct driver, (connection) → Pcf8574Minimal

        val p0 = chip.pin(0)                                           // get pin proxy, (n) → Pin
        p0.setOutput()                                                 // set output mode — drives P0 low, () → Unit

        val p4 = chip.pin(4)                                           // get pin proxy, (n) → Pin
        p4.setInput()                                                  // set input mode — release P4 to quasi-input, () → Unit

        while (true) {
            val port = chip.readPort()                                 // read all 8 pins, () → Int bitmask
            if ((port shr 4 and 1) == 0) p0.setLow()                  // drive P0 low (LED on), () → Unit
            else                         p0.setHigh()                  // release P0 high (LED off), () → Unit
            Thread.sleep(200)
        }
    }
}
