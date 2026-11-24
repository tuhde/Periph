///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-kotlin:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.HT16K33Minimal

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x70
    I2CConnection(bus, addr).use { connection ->                                   // open I²C bus, (bus, address=0x70) → I2CConnection
        val matrix = HT16K33Minimal(connection)                      // construct driver, (connection) → HT16K33Minimal

        for (row in 0 until HT16K33Minimal.ROWS) {
            matrix.setPixel(0, row)                                      // set one LED in the shadow RAM, (com 0–7, row 0–15, on) → void
        }
        matrix.show()                                                    // write the shadow RAM to the chip, () → void
        Thread.sleep(1000)
    }
}
