///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.SHT31Full
import java.util.function.IntConsumer

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x44").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = SHT31Full(connection)                                                  // Create SHT31 driver, (connection) → void

        sensor.softReset()                                                                  // Soft reset, () → void
        val status = sensor.readStatus()                                                    // Read status register, () → int
        println("status 0x%04X".format(status))
        // --- Single shots at all three repeatability levels ---
        for (level in listOf("low", "medium", "high")) {
            sensor.setRepeatability(level)                                                  // Select repeatability, (level) → void
            val t0 = System.nanoTime()
            val v = sensor.read()                                                           // Read temperature and humidity, () → double[]
            println("%-6s %.2f C %.1f %%RH (%.1f ms)".format(level, v[0], v[1], (System.nanoTime() - t0) / 1e6))
        }
        sensor.setClockStretching(false)                                                    // Use clock-stretching commands, (on) → void
        println("raw %s T %.2f RH %.1f".format(java.util.Arrays.toString(sensor.readRaw()), sensor.readTemperature(), sensor.readHumidity()))
        // --- Periodic mode at 2 mps: ten samples ---
        sensor.startPeriodic(2.0, "high", false)                                            // Start periodic mode, (rate, repeatability, art) → void
        var got = 0
        while (got < 10) {
            val res = sensor.fetch()                                                        // Fetch the latest periodic result, () → double[] or null
            if (res == null) { Thread.sleep(50); continue }
            got++
            println("periodic %d: %.2f C %.1f %%RH".format(got, res[0], res[1]))
        }
        sensor.stopPeriodic()                                                               // Stop periodic mode, () → void
        // --- Heater test: 10 s ---
        val before = sensor.readTemperature()                                               // Read temperature only, () → double °C
        sensor.setHeater(true)                                                              // Switch the heater, (on) → void
        Thread.sleep(10000)
        println("heater status bit: %s".format((sensor.readStatus() & 0x2000) != 0))
        println("temperature rise: %.2f K".format(sensor.readTemperature() - before))
        sensor.setHeater(false)                                                             // Switch the heater, (on) → void
        sensor.clearStatus()                                                                // Clear status flags, () → void
        println("status 0x%04X, dew point %.2f C".format(sensor.readStatus(), sensor.dewPoint()))
    }
}
