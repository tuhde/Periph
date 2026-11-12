///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.CS43L22Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, CS43L22Minimal.DEFAULT_ADDRESS).use { connection ->                           // open I²C bus, device address, (bus, address=0x4A) → I2CConnection
        val dac = CS43L22Minimal(connection)                                                         // Create CS43L22 driver, (connection, resPin=null) → CS43L22Minimal
        dac.init()                                                                                   // Reset, verify ID, load defaults, () → Unit
        dac.start()                                                                                  // Power up the DAC once MCLK is running, () → Unit
        dac.setVolume(-20.0)                                                                         // Set master volume, (db -102–12 dB) → Unit
        dac.mute(false)                                                                              // Unmute the headphone outputs, (on) → Unit
        repeat(5) {
            println(dac.getVolume())                                                                 // Read back master volume, () → double dB
            Thread.sleep(1000)
        }
        dac.stop()                                                                                   // Power down the DAC, () → Unit
    }
}
