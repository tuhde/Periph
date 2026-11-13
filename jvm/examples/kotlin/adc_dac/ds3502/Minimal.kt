///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.DS3502Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, DS3502Minimal.DEFAULT_ADDRESS).use { connection ->                          // open I²C bus, device address, (bus, address=0x28) → I2CConnection
        val pot = DS3502Minimal(connection, 15.0, 0.0)                                             // construct driver, (connection, vHigh=1.0 V, vLow=0.0 V) → DS3502Minimal
        val codes = intArrayOf(0, 32, 64, 96, 127)
        for (code in codes) {
            pot.setWiper(code)                                                                     // Set wiper position, (code 0–127) → void
            println("${code} ${pot.getWiper()}")                                                   // Read wiper position, () → int 0–127
            println(pot.getVoltage())                                                              // Read unloaded RW voltage, () → double V
            Thread.sleep(500)
        }
        pot.setVoltage(6.0)                                                                        // Set RW output voltage, (volts V) → void
    }
}
