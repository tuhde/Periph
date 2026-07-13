///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.environmental.Bme680Minimal

fun main() {
    I2CTransport(1, 0x76).use { transport ->             // open I²C bus 1, device 0x76, (bus, address=0x76) → I2CTransport
        val sensor = Bme680Minimal(transport)                   // construct driver, verifies chip ID and loads calibration, (transport) → Bme680Minimal

        while (true) {
            val t = sensor.temperature()                        // read temperature, () → Double °C
            val p = sensor.pressure()                           // read pressure, () → Double hPa
            val h = sensor.humidity()                           // read humidity, () → Double %RH
            val g = sensor.gasResistance()                      // read gas resistance, () → Double Ω
            println("temperature=%.2f °C  pressure=%.2f hPa  humidity=%.1f %%RH  gas=%.0f Ω".format(t, p, h, g))
            Thread.sleep(1000)
        }
    }
}
