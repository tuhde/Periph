///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-kotlin:1.3.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS1013Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, ADS1013Minimal.DEFAULT_ADDRESS).use { connection ->   // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val adc = ADS1013Minimal(connection)                                            // construct driver, (connection) → ADS1013Minimal

        repeat(10) {
            val raw = adc.readRaw()                                                     // Read raw code, () → int (−2048…2047)
            val v = adc.readVoltage()                                                   // Read voltage, () → double V
            println("%d -> %.3f V".format(raw, v))
            Thread.sleep(1000)
        }
    }
}
