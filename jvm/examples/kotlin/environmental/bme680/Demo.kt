///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.environmental.Bme680Full

/**
 * Room air quality probe: 5-minute run at 0.2 Hz with gas sensor for VOC detection.
 *
 * Polls all four BME680 channels (temperature, pressure, humidity, gas resistance)
 * every 5 seconds for 60 ticks. At tick 30 the user is prompted to expose the
 * sensor to a VOC source (e.g. a marker pen or breath). After the run, prints
 * min/max/mean for all channels and the VOC response ratio (baseline vs exposed
 * gas resistance).
 */
fun main() {
    val samples    = 60
    val intervalMs = 5000L
    val vocTick    = 30

    val bus  = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.toInt(16) ?: 0x76
    I2CTransport(bus, addr).use { transport ->           // open I²C bus, (bus, address=0x76) → I2CTransport
        val sensor = Bme680Full(transport)                      // construct driver, verifies chip ID, reads calibration, (transport) → Bme680Full

        // --- Configure for room air quality monitoring ---
        // ×4 oversampling on temperature and pressure gives good SNR for
        // environmental logging. ×2 humidity oversampling balances accuracy
        // with conversion time. IIR filter ×3 smooths pressure steps from
        // doors and windows without lagging behind real weather changes.
        sensor.configure(                                       // configure oversampling, mode, filter, (osrsT, osrsP, osrsH, mode, filter) → Unit
            Bme680Full.OSRS_X4,
            Bme680Full.OSRS_X4,
            Bme680Full.OSRS_X2,
            Bme680Full.MODE_FORCED,
            Bme680Full.FILTER_3)

        // --- Configure heater for gas sensing ---
        // 320 °C for 150 ms is the Bosch-recommended default for indoor
        // air quality. The heater burns off residual contaminants on the
        // MOx surface so the resistance reading reflects current VOC levels.
        sensor.setHeater(320, 150)                              // configure heater profile 0, (tempC=320 °C, durationMs=150 ms) → Unit

        val temps      = DoubleArray(samples)
        val pressures  = DoubleArray(samples)
        val humidities = DoubleArray(samples)
        val gasValues  = DoubleArray(samples)

        var baselineGas  = 0.0
        var exposedGas   = 0.0
        var baselineCount = 0
        var exposedCount  = 0

        // --- 60-tick sampling loop at 5-second intervals ---
        // Each iteration triggers a single TPHG cycle and records all four
        // values. At tick 30 the user is prompted to introduce a VOC source
        // so the gas resistance drop can be observed in the second half.
        for (i in 0 until samples) {
            if (i == vocTick) {
                println("\n>>> Expose sensor to VOC source now (e.g. marker pen, breath) <<<\n")
            }

            val reading = sensor.readAll()                      // read all four values from one TPHG cycle, () → Reading
            val t = reading.temperatureC
            val p = reading.pressureHpa
            val h = reading.humidityPct
            val g = reading.gasResistanceOhm

            temps[i]      = t
            pressures[i]  = p
            humidities[i] = h
            gasValues[i]  = g

            if (!g.isNaN()) {
                if (i < vocTick) {
                    baselineGas += g
                    baselineCount++
                } else {
                    exposedGas += g
                    exposedCount++
                }
            }

            val gasStr = if (g.isNaN()) "invalid" else "%.0f Ω".format(g)
            println("[%2d] temperature=%.2f °C  pressure=%.2f hPa  humidity=%.1f %%RH  gas=%s"
                .format(i + 1, t, p, h, gasStr))

            Thread.sleep(intervalMs)
        }

        // --- Print summary statistics ---
        // After the run, report the full range and mean for each channel so
        // the user can see how the environment changed during the session.
        val minT = temps.min();     val maxT = temps.max();     val sumT = temps.sum()
        val minP = pressures.min(); val maxP = pressures.max(); val sumP = pressures.sum()
        val minH = humidities.min(); val maxH = humidities.max(); val sumH = humidities.sum()
        println("\n--- Summary ($samples samples) ---")
        println("Temperature: min=%.2f °C  max=%.2f °C  mean=%.2f °C".format(minT, maxT, sumT / samples))
        println("Pressure:    min=%.2f hPa  max=%.2f hPa  mean=%.2f hPa".format(minP, maxP, sumP / samples))
        println("Humidity:    min=%.1f %%RH  max=%.1f %%RH  mean=%.1f %%RH".format(minH, maxH, sumH / samples))

        // --- VOC response analysis ---
        // Compare the mean gas resistance before and after VOC exposure.
        // A significant drop indicates the MOx sensor detected VOCs; the
        // ratio quantifies the sensor's response magnitude.
        if (baselineCount > 0 && exposedCount > 0) {
            val meanBaseline = baselineGas / baselineCount
            val meanExposed  = exposedGas / exposedCount
            val ratio        = meanBaseline / meanExposed
            println("\n--- VOC Response ---")
            println("Baseline gas resistance: %.0f Ω (mean of %d readings)".format(meanBaseline, baselineCount))
            println("Exposed gas resistance:  %.0f Ω (mean of %d readings)".format(meanExposed, exposedCount))
            println("Response ratio:          %.2f× (>1 = VOC detected)".format(ratio))
        } else {
            println("\n--- VOC Response ---")
            println("Insufficient valid gas readings for VOC analysis.")
        }
    }
}
