///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.magnetometer.As5600Minimal

fun main() {
    I2CTransport(1, 0x36).use { transport ->                 // open I²C bus 1, device 0x36, (bus, address) → I2CTransport
        val as5600 = As5600Minimal(transport)                      // construct driver, (transport) → As5600Minimal

        repeat(10) {
            val angle = as5600.angle()       // read angle in degrees, () → Double deg
            val raw   = as5600.angleRaw()    // read raw angle count, () → Int 0–4095
            val md    = as5600.isMagnetDetected()  // check magnet detected, () → Boolean
            println("angle=%.2f°  raw=%d  magnet=%s".format(angle, raw, if (md) "OK" else "NONE"))
            Thread.sleep(100)
        }
    }
}
