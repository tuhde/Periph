///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.comms.RDA5807MMinimal

fun main() {
    I2CConnection(1, 0x10).use { connection ->                    // open I²C bus 1, device 0x10, (bus, address) → I2CConnection
        val fm = RDA5807MMinimal(connection, 100.0, 8)            // construct driver, (connection, frequencyMhz=100.0, volume=8) → RDA5807MMinimal

        while (true) {
            val freq = fm.seek(true)   // seek to next station, (up=true) → Double?
            if (freq != null) {
                println("%.2f MHz".format(freq))
            }
            Thread.sleep(3000)
        }
    }
}
