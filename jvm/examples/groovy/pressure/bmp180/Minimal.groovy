///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.pressure.Bmp180Minimal

def pi4j = Pi4J.newAutoContext()                                // initialise Pi4J, () → Context
try {
    def transport = new I2CTransport(pi4j, 1, 0x77)            // open I²C bus 1, device 0x77, (bus, address=0x77) → I2CTransport
    try {
        def sensor = new Bmp180Minimal(transport)               // construct driver, verifies chip ID and loads calibration, (transport) → Bmp180Minimal

        while (true) {
            double t = sensor.temperature()                     // read temperature, () → double °C
            double p = sensor.pressure()                        // read pressure, () → double hPa
            printf("temperature=%.2f °C  pressure=%.2f hPa%n", t, p)
            Thread.sleep(1000)
        }
    } finally {
        transport.close()
    }
} finally {
    pi4j.shutdown()
}
