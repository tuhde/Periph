///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.chips.environmental.Bme280Full
import it.uhde.periph.transport.I2CTransport

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.toInt(16) ?: 0x76
    I2CTransport(bus, addr).use { transport ->

        // --- Weather monitoring preset: forced mode, ×1/×1/×1, filter off ---
        // BME280 datasheet "weather monitoring" preset: minimum power,
        // single-shot, 8 ms typ / 9.3 ms max per cycle. Sleep between
        // samples to demonstrate battery-friendly indoor monitoring.
        val sensor = Bme280Full(transport)                  // construct driver, verifies chip ID and loads calibration, (transport) → Bme280Full
        sensor.configure(Bme280Full.OSRS_X1, Bme280Full.OSRS_X1, Bme280Full.OSRS_X1, Bme280Full.MODE_FORCED, Bme280Full.FILTER_OFF, Bme280Full.T_SB_0_5_MS)  // configure chip, (osrsT=×1, osrsP=×1, osrsH=×1, mode=forced, filter=off, tSb=0) → Unit

        for (n in 0 until 10) {
            val t = sensor.temperature()                    // read temperature, () → Double °C
            val p = sensor.pressure()                       // read pressure, () → Double hPa
            val h = sensor.humidity()                       // read humidity, () → Double %RH
            val a = sensor.altitude()                       // compute altitude, (seaLevelHpa=1013.25) → Double m
            val d = sensor.dewPoint()                       // compute dew point, () → Double °C
            println("$n: %.1f °C, %.1f %%RH, %.1f hPa, dew=%.1f °C, alt=%.1f m".format(t, h, p, d, a))
            Thread.sleep(1000)
        }

        // --- Half-way: breathe gently on the sensor for 3 seconds ---
        // User exposes the sensor to humid exhaled air; humidity climbs
        // from ~40 %RH toward ~80 %RH, dew point spikes toward ambient
        // temperature, pressure stays flat, temperature rises only
        // slightly. Demonstrates the humidity channel's response and
        // the dew-point alarm use case.
        println("--- Breathe gently on the sensor for 3 seconds ---")
        Thread.sleep(3000)
        {
            val t = sensor.temperature()                    // read temperature, () → Double °C
            val p = sensor.pressure()                       // read pressure, () → Double hPa
            val h = sensor.humidity()                       // read humidity, () → Double %RH
            val d = sensor.dewPoint()                       // compute dew point, () → Double °C
            println("after breath: %.1f °C, %.1f %%RH, %.1f hPa, dew=%.1f °C".format(t, h, p, d))
        }()
    }
}
