///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.SiPoConnection
import it.uhde.periph.chips.io_expander.Tpic6b595Minimal

fun main() {
    SiPoConnection.hardware(0, 0, 17, -1, -1).use { connection ->     // open SiPo connection, (bus, device, rckLine, srclrLine, gLine) → SiPoConnection
        val chip = Tpic6b595Minimal(connection)                       // construct driver, (connection, numDevices=1) → Tpic6b595Minimal
                                                                       // initialises every output to OFF (shadow zeroed, latched once)

        val p0 = chip.pin(0)                                          // get pin proxy, (n=0) → Pin
        val p7 = chip.pin(7)                                          // get pin proxy, (n=7) → Pin

        while (true) {
            p0.setHigh()                                              // set DRAIN0 ON, () → Unit
            p7.setLow()                                               // set DRAIN7 OFF, () → Unit
            Thread.sleep(500)
            p0.setLow()                                               // set DRAIN0 OFF, () → Unit
            p7.setHigh()                                              // set DRAIN7 ON, () → Unit
            Thread.sleep(500)
        }
    }
}
