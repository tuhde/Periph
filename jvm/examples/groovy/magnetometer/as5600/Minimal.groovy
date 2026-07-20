///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.magnetometer.As5600Minimal

def transport = new I2CTransport(1, 0x36)               // open I²C bus 1, device 0x36, (bus, address) → I2CTransport
try {
    def as5600 = new As5600Minimal(transport)                  // construct driver, (transport) → As5600Minimal

    10.times {
        double angle = as5600.angle()       // read angle in degrees, () → double deg
        int    raw   = as5600.angleRaw()    // read raw angle count, () → int 0–4095
        boolean md   = as5600.isMagnetDetected()  // check magnet detected, () → boolean
        printf("angle=%.2f°  raw=%d  magnet=%s%n", angle, raw, md ? "OK" : "NONE")
        Thread.sleep(100)
    }
} finally {
    transport.close()
}
