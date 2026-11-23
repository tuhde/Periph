///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-kotlin:1.5.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ltc4151Minimal

fun main() {
    I2CConnection(1, 0x6A).use { connection ->              // open I²C bus 1, device 0x6A, (bus, address) → I2CConnection
        val ltc = Ltc4151Minimal(connection, 0.02)          // construct driver, (connection, rSense=0.02 Ω) → Ltc4151Minimal

        repeat(10) {
            val v = ltc.voltage()                           // read supply voltage, () → Double V
            val c = ltc.current()                           // read load current, () → Double A
            val p = ltc.power()                             // read load power, () → Double W
            println("V=%.3f V  I=%.4f A  P=%.3f W".format(v, c, p))
            Thread.sleep(1000)
        }
    }
}
