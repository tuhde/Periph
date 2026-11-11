///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS7828Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, ADS7828Minimal.DEFAULT_ADDRESS).use { connection ->              // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val adc = ADS7828Minimal(connection)                                                        // construct driver, (connection, vref=null → internal 2.5 V) → ADS7828Minimal
        adc.init()                                                                                  // Enable internal reference, () → Unit
        val raw = adc.readRaw(0)                                                                    // Read CH0 raw code, (channel 0–7) → int
        val v0 = adc.readVoltage(0)                                                                 // Read CH0 voltage, (channel 0–7) → double V
        val all = adc.readAll()                                                                     // Read all eight channels, () → double[8] V
        println("$raw $v0 ${all[7]}")
    }
}
