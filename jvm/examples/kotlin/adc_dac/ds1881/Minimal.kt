///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.DS1881Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, DS1881Minimal.DEFAULT_ADDRESS).use { connection ->                               // open I²C bus, device address, (bus, address=0x28) → I2CConnection
        val amp = DS1881Minimal(connection)                                                         // construct driver, (connection) → DS1881Minimal
        amp.init()                                                                                  // Apply defaults, () → Unit
        for (db in doubleArrayOf(0.0, 10.0, 20.0, 40.0)) {
            amp.setAttenuation(0, db)                                                               // Set attenuation, (channel 0–1, db dB) → Unit
            amp.setAttenuation(1, db)                                                               // Set attenuation, (channel 0–1, db dB) → Unit
            println("" + db + " " + amp.getAttenuation(0))                                          // Read attenuation, (channel 0–1) → double dB
            Thread.sleep(500)
        }
        amp.mute(0)                                                                                 // Mute channel, (channel 0–1) → Unit
    }
}
