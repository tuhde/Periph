///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.MCP4XXXMinimal

fun main() {
    I2CConnection(1, 0x58).use { connection ->                              // open I²C bus 1, device 0x58, (bus, address) → I2CConnection
        val pot = MCP4XXXMinimal(connection, "MCP4532", 10000.0, 75.0)      // construct driver, (connection, part="MCP4532", rAb=10000 Ω, rWiper=75 Ω) → MCP4XXXMinimal
        pot.init()                                                          // bus check, () → Unit

        pot.setWiper(32, 0)                                                 // set wiper, (code 0-128, channel=0) → Unit
        pot.setResistance(2200.0, 0)                                        // set W-B resistance, (ohms, channel=0) → Unit

        repeat(10) {
            val code = pot.getWiper(0)                                      // read wiper, (channel=0) → Int 0-128
            val ohms = pot.getResistance(0)                                 // read W-B resistance, (channel=0) → Double Ω
            println("code=%d  R_WB=%.0f ohm".format(code, ohms))
            Thread.sleep(1000)
        }
    }
}
