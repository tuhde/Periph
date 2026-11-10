///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.MCP4XXXMinimal

def connection = new I2CConnection(1, 0x58)                                 // open I²C bus 1, device 0x58, (bus, address) → I2CConnection
try {
    def pot = new MCP4XXXMinimal(connection, 'MCP4532', 10000.0d, 75.0d)    // construct driver, (connection, part="MCP4532", rAb=10000 Ω, rWiper=75 Ω) → MCP4XXXMinimal
    pot.init()                                                              // bus check, () → void

    pot.setWiper(32, 0)                                                     // set wiper, (code 0-128, channel=0) → void
    pot.setResistance(2200.0d, 0)                                           // set W-B resistance, (ohms, channel=0) → void

    10.times {
        int code = pot.getWiper(0)                                          // read wiper, (channel=0) → int 0-128
        double ohms = pot.getResistance(0)                                  // read W-B resistance, (channel=0) → double Ω
        printf("code=%d  R_WB=%.0f ohm%n", code, ohms)
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
