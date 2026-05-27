///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.gas.Ens160Minimal

fun main() {
    I2CTransport(1, 0x52).use { transport ->             // open I²C bus 1, device 0x52, (bus, address=0x52) → I2CTransport
        val sensor = Ens160Minimal(transport)                   // construct driver, verifies PART_ID and starts STANDARD mode, (transport) → Ens160Minimal

        println("Waiting for sensor warm-up...")
        while (sensor.status() != 0) {                          // poll validity, () → Int 0–3
            println("Status: ${sensor.status()}")
            Thread.sleep(1000)
        }

        for (i in 0 until 10) {
            val data = sensor.readAirQuality()                  // read air quality, () → DoubleArray {aqi, tvocPpb, eco2Ppm}
            println("AQI=${data[0].toInt()} TVOC=${data[1].toInt()} ppb eCO2=${data[2].toInt()} ppm")
            Thread.sleep(1000)
        }
    }
}
