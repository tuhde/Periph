///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.gas.Ens160Full

/**
 * Indoor air quality monitor: 60-second run with human-readable AQI labels.
 *
 * The demo waits for sensor warm-up (VALIDITY_FLAG=0), then reads AQI, TVOC,
 * and eCO2 every second. AQI 1-2 is acceptable for occupied spaces; AQI 3+
 * suggests ventilation.
 */
fun main() {
    val aqiLabels = mapOf(1 to "Excellent", 2 to "Good", 3 to "Moderate", 4 to "Poor", 5 to "Unhealthy")

    I2CTransport(1, 0x52).use { transport ->             // open I²C bus 1, device 0x52 (ADDR low), (bus, address=0x52) → I2CTransport
        val sensor = Ens160Full(transport)                      // construct driver, verifies PART_ID, starts STANDARD mode, (transport) → Ens160Full

        // --- Wait for sensor warm-up ---
        // The ENS160 requires ~3 minutes after power-on or idle before VALIDITY_FLAG
        // reaches 0. During warm-up, readings are unreliable. The driver surfaces the
        // status so the application can display progress to the user.
        println("Waiting for sensor warm-up...")
        while (sensor.status() != 0) {                          // poll validity, () → Int 0–3
            val s = sensor.status()
            when (s) {
                1 -> println("Warm-up in progress...")
                2 -> println("Initial start-up (first power-on, up to 1 hour)...")
                else -> println("No valid output")
            }
            Thread.sleep(1000)
        }
        println("Sensor ready!")

        // --- Set compensation from external sensor ---
        // If an external temperature/humidity sensor is available, feeding its readings
        // to the ENS160 improves accuracy outside the 20-80%RH range. Here we use a
        // fixed 22C/45%RH as an example.
        sensor.setCompensation(22.0, 45.0)                      // set compensation, (tempCelsius=22.0, rhPercent=45.0) → Unit

        // --- Indoor air quality monitoring loop ---
        // Reads AQI, TVOC, and eCO2 every second and prints a human-readable label.
        // AQI 1-2 is acceptable for occupied spaces; AQI 3+ suggests ventilation.
        for (n in 0 until 60) {
            val data = sensor.readAirQuality()                  // read air quality, () → DoubleArray {aqi, tvocPpb, eco2Ppm}
            val aqi = data[0].toInt()
            val label = aqiLabels[aqi] ?: "Unknown"
            println("${n}s: AQI=$aqi ($label) TVOC=${data[1].toInt()} ppb eCO2=${data[2].toInt()} ppm")
            Thread.sleep(1000)
        }
    }
}
