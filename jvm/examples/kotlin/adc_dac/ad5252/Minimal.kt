///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-kotlin:1.5.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.AD5252Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, AD5252Minimal.DEFAULT_ADDRESS).use { connection ->  // open I²C bus, device address, (bus, address=0x2C) → I2CConnection
        val pot = AD5252Minimal(connection)                               // construct driver, (connection, rAb=10000.0 Ω, rWiper=75.0 Ω) → AD5252Minimal
        val codes = intArrayOf(0, 64, 128, 192, 255)
        for (code in codes) {
            pot.setWiper(0, code)                                                                   // Set wiper position, (channel 0–1, code 0–255) → Unit
            println("$code ${pot.getWiper(0)}")                                                     // Read wiper position, (channel 0–1) → int 0–255
            println(pot.getResistance(0))                                                           // Read W–B resistance, (channel 0–1) → double Ω
            Thread.sleep(500)
        }
        pot.setResistance(1, 2200.0)                                                                // Set W–B resistance, (channel 0–1, ohms Ω) → Unit
    }
}
