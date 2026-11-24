///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-kotlin:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.environmental.Bme688Full

/**
 * Room air probe with parallel mode.
 *
 * Part 1: ten forced-mode readings (T, RH, hPa, Ω, dew point), one per 5 s.
 * Part 2: parallel mode with a 3-step heater profile (320, 250 and 180 °C;
 * repeats 1, 2, 1; shared wait 100 ms) for one minute, printing the gas
 * resistance of every heater step, then back to sleep.
 */
fun main() {
    val bus  = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x76
    I2CConnection(bus, addr).use { connection ->                     // open I²C bus, (bus, address=0x76) → I2CConnection
        val sensor = Bme688Full(connection)                          // construct driver, verifies chip ID, reads calibration, (connection) → Bme688Full
        sensor.configure(Bme688Full.OSRS_X2, Bme688Full.OSRS_X16, Bme688Full.OSRS_X1,
            Bme688Full.MODE_SLEEP, Bme688Full.FILTER_15)             // configure oversampling, mode, filter, (osrsT, osrsP, osrsH, mode, filter) → Unit
        sensor.setHeater(320, 150)                                   // configure heater profile 0, (tempC=320 °C, durationMs=150 ms) → Unit

        println("--- forced mode ---")
        for (i in 0 until 10) {
            val r = sensor.readAll()                                 // read all four values from one TPHG cycle, () → Reading
            val gm = Math.log(Math.max(r.humidityPct, 0.01) / 100.0) + 17.62 * r.temperatureC / (243.12 + r.temperatureC)
            val dew = 243.12 * gm / (17.62 - gm)                     // Magnus dew point, °C
            println("%d: %.1f C, %.1f %%RH, %.1f hPa, %.0f Ohm, dew %.1f C".format(
                i, r.temperatureC, r.humidityPct, r.pressureHpa, r.gasResistanceOhm, dew))
            Thread.sleep(5000)
        }

        println("--- parallel mode ---")
        sensor.startParallel(intArrayOf(320, 250, 180), intArrayOf(1, 2, 1), 100.0) // start parallel mode, (tempsC, repeats, sharedWaitMs) → Unit
        repeat(60) {
            for (f in sensor.readParallel()) {                       // read the new data fields, () → List<Field>
                println("step %d #%d: %.1f C, %.1f %%RH, %.0f Ohm (valid=%b, stable=%b)".format(
                    f.step, f.subIndex, f.temperatureC, f.humidityPct, f.gasResistanceOhm, f.gasValid, f.heaterStable))
            }
            Thread.sleep(1000)
        }
        sensor.stopParallel()                                        // back to sleep, () → Unit
    }
}
