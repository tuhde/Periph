///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.memory.AT24C02NMinimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x50").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val ee = AT24C02NMinimal(connection)                                               // Create AT24C02N driver, (connection) → void

        var counter = 0
        while (true) {
            ee.writeByte(0x0000, counter)                                                   // Write a single byte, (address 0-255, value 0-255) → void
            val v = ee.readByte(0x0000)                                                     // Read a single byte, (address 0-255) → int
            println("Stored: %d".format(v))
            counter = (counter + 1) and 0xFF
            Thread.sleep(1000)
        }
    }
}
