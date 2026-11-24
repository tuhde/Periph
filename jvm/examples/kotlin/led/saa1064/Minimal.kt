///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-kotlin:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.SAA1064Minimal

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x38
    I2CConnection(bus, addr).use { connection ->                                // open I²C bus, (bus, address=0x38) → I2CConnection
        val display = SAA1064Minimal(connection)                        // construct driver, (connection) → SAA1064Minimal
        for (n in 0 until 100) {
            display.showNumber(n)                                       // show a right-aligned number, (value 0–9999) → void
            Thread.sleep(100)
        }
    }
}
