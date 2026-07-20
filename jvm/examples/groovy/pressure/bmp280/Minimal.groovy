///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.pressure.Bmp280Minimal

def transport = new I2CTransport(1, 0x76)            // open I²C bus 1, device 0x76, (bus, address=0x76) → I2CTransport
try {
    def sensor = new Bmp280Minimal(transport)               // construct driver, verifies chip ID and loads calibration, (transport) → Bmp280Minimal

    while (true) {
        double t = sensor.temperature()                     // read temperature, () → double °C
        double p = sensor.pressure()                        // read pressure, () → double hPa
        printf("temperature=%.2f °C  pressure=%.2f hPa%n", t, p)
        Thread.sleep(1000)
    }
} finally {
    transport.close()
}
