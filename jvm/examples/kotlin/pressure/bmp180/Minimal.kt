///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.pressure.Bmp180Minimal

fun main() {
    val pi4j = Pi4J.newAutoContext()                                // initialise Pi4J, () → Context
    try {
        I2CTransport(pi4j, 1, 0x77).use { transport ->             // open I²C bus 1, device 0x77, (bus, address=0x77) → I2CTransport
            val sensor = Bmp180Minimal(transport)                   // construct driver, verifies chip ID and loads calibration, (transport) → Bmp180Minimal

            while (true) {
                val t = sensor.temperature()                        // read temperature, () → Double °C
                val p = sensor.pressure()                           // read pressure, () → Double hPa
                println("temperature=%.2f °C  pressure=%.2f hPa".format(t, p))
                Thread.sleep(1000)
            }
        }
    } finally {
        pi4j.shutdown()
    }
}
