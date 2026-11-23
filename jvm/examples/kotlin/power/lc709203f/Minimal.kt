///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-kotlin:1.5.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.LC709203FMinimal

fun main() {
    I2CConnection(1, 0x0B).use { connection ->   // open I²C bus 1, device 0x0B, (bus, address) → I2CConnection
        val gauge = LC709203FMinimal(connection, 1000, 1)   // construct driver, (connection, capacityMah, profile) → LC709203FMinimal

        repeat(10) {
            val v = gauge.voltage()     // read cell voltage, () → Double V
            val p = gauge.percent()     // read state of charge, () → Double %
            val t = gauge.temperature() // read cell temperature, () → Double °C
            println("%.3f V  %.1f %%  %.2f C".format(v, p, t))
            Thread.sleep(5000)
        }
    }
}
