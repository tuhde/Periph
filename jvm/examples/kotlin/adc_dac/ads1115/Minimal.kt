///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS1115Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, ADS1115Minimal.DEFAULT_ADDRESS).use { connection ->                 // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val adc = ADS1115Minimal(connection)                                                // construct driver, (connection) → ADS1115Minimal

        repeat(10) {
            val v = adc.readVoltage(0)                                                      // Read voltage, (channel=0) → double V
            println("%.5f V".format(v))
            Thread.sleep(1000)
        }
    }
}
