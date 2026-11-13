///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.other.PCA9541Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, PCA9541Minimal.DEFAULT_ADDRESS).use { connection ->                         // open I²C bus, device address, (bus, address=0x70) → I2CConnection
        val selector = PCA9541Minimal(connection)                                                  // construct driver, (connection) → PCA9541Minimal
        println("has control " + selector.hasControl())                                            // Report control, () → boolean
        println("acquire " + selector.acquire())                                                   // Take the downstream bus, (busInit=false, timeoutMs=20) → boolean
        Thread.sleep(1000)
        selector.release()                                                                         // Give the bus to the other master, () → void
        println("has control " + selector.hasControl())
    }
}
