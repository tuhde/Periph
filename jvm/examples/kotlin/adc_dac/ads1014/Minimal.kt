///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS1014Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, ADS1014Minimal.DEFAULT_ADDRESS).use { connection ->   // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val adc = ADS1014Minimal(connection)                                            // construct driver, (connection) → ADS1014Minimal

        repeat(10) {
            val raw = adc.readRaw()                                                     // Read raw code, () → int (−2048…2047)
            val v = adc.readVoltage()                                                   // Read voltage, () → double V
            println("%d -> %.3f V".format(raw, v))
            Thread.sleep(1000)
        }
    }
}
