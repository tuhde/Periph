///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gas.Ens160Minimal

fun main() {
    I2CConnection(1, 0x52).use { connection ->             // open I²C bus 1, device 0x52, (bus, address=0x52) → I2CConnection
        val sensor = Ens160Minimal(connection)                   // construct driver, verifies PART_ID and starts STANDARD mode, (connection) → Ens160Minimal

        println("Waiting for sensor warm-up...")
        while (true) {                                          // Wait for valid data, () → blocks until warm
            try { sensor.readAirQuality(); break } catch (e: Exception) { Thread.sleep(1000) }
        }

        for (i in 0 until 10) {
            val data = sensor.readAirQuality()                  // read air quality, () → DoubleArray {aqi, tvocPpb, eco2Ppm}
            println("AQI=${data[0].toInt()} TVOC=${data[1].toInt()} ppb eCO2=${data[2].toInt()} ppm")
            Thread.sleep(1000)
        }
    }
}
