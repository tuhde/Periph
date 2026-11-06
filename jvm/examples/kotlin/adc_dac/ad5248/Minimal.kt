///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.AD5248Minimal

fun main() {
    I2CConnection(1, 0x2C).use { connection ->                   // open I²C bus 1, device 0x2C, (bus, address) → I2CConnection
        val pot = AD5248Minimal(connection, 10000.0, 160.0)      // construct driver, (connection, rAb=10000 Ω, rWiper=160 Ω) → AD5248Minimal

        pot.setWiper(0, 64)                                      // set channel 0 wiper, (channel 0-1, code 0-255) → void
        pot.setResistance(1, 2200.0)                             // set channel 1 W-B resistance, (channel 0-1, ohms) → void

        repeat(10) {
            val code = pot.getWiper(0)                           // read channel 0 wiper, (channel 0-1) → int 0–255
            val ohms = pot.getResistance(0)                      // read channel 0 W-B resistance, (channel 0-1) → double Ω
            println("ch0 code=%d  R_WB=%.0f ohm".format(code, ohms))
            Thread.sleep(1000)
        }
    }
}
