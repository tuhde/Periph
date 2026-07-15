///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.pressure.Bmp280Minimal

fun main() {
    I2CTransport(1, 0x76).use { transport ->             // open I²C bus 1, device 0x76, (bus, address=0x76) → I2CTransport
        val sensor = Bmp280Minimal(transport)                   // construct driver, verifies chip ID and loads calibration, (transport) → Bmp280Minimal

        while (true) {
            val t = sensor.temperature()                        // read temperature, () → Double °C
            val p = sensor.pressure()                           // read pressure, () → Double hPa
            println("temperature=%.2f °C  pressure=%.2f hPa".format(t, p))
            Thread.sleep(1000)
        }
    }
}
