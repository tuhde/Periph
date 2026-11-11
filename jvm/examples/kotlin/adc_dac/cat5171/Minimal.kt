///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.CAT5171Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, CAT5171Minimal.DEFAULT_ADDRESS).use { connection ->                          // open I²C bus, device address, (bus, address=0x2C) → I2CConnection
        val pot = CAT5171Minimal(connection)                                                        // construct driver, (connection, rAb=100000.0 Ω, rWiper=50.0 Ω) → CAT5171Minimal
        val codes = intArrayOf(0, 64, 128, 192, 255)
        for (code in codes) {
            pot.setWiper(code)                                                                      // Set wiper position, (code 0–255) → Unit
            println("${code} ${pot.getWiper()}")                                                    // Read wiper position, () → int 0–255
            println(pot.getResistance())                                                            // Read W–B resistance, () → double Ω
            Thread.sleep(500)
        }
        pot.setResistance(22000.0)                                                                  // Set W–B resistance, (ohms Ω) → Unit
    }
}
