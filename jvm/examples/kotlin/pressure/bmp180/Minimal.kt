///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.pressure.Bmp180Minimal

fun main() {
    I2CTransport(1, 0x77).use { transport ->             // open I²C bus 1, device 0x77, (bus, address=0x77) → I2CTransport
        val sensor = Bmp180Minimal(transport)                   // construct driver, verifies chip ID and loads calibration, (transport) → Bmp180Minimal

        while (true) {
            val t = sensor.temperature()                        // read temperature, () → Double °C
            val p = sensor.pressure()                           // read pressure, () → Double hPa
            println("temperature=%.2f °C  pressure=%.2f hPa".format(t, p))
            Thread.sleep(1000)
        }
    }

}
