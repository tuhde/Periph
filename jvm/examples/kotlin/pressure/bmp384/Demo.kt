///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp384Full

fun main() {
    I2CConnection(1, 0x76).use { connection ->              // open I²C bus 1, device 0x76, (bus, address=0x76) → I2CConnection
        val sensor = Bmp384Full(connection)                  // construct full driver, (connection) → Bmp384Full

        // --- Configure for noise-sensitive altitude logging ---
        // osr_p=×16 gives ~12 cm noise-equivalent altitude resolution; the IIR
        // coefficient 3 suppresses door-slam / gust spikes without too much step lag.
        // ODR=25 Hz gives us a sample every 40 ms, well above the ~38 ms T_conv.
        sensor.configure(4, 1, 2, 0x03)                      // configure oversampling/IIR/ODR, (osrP 0–5, osrT 0–5, iirFilter 0–7, odrSel 0x00–0x11) → Unit
        sensor.setMode(Bmp384Full.MODE_NORMAL)               // set power mode, (mode) → Unit

        // --- Sample for 30 seconds, logging altitude every 500 ms ---
        // P0 = 1013.25 hPa (ISA sea-level reference). 30 s × 2 Hz = 60 rows.
        val SEA_LEVEL_HPA = 1013.25
        val start = System.currentTimeMillis()
        var next = start
        var rows = 0
        while (System.currentTimeMillis() - start < 30_000) {
            val now = System.currentTimeMillis()
            if (now >= next) {
                val t = sensor.temperature()                // read temperature, () → Double °C
                val p = sensor.pressure()                   // read pressure, () → Double hPa
                val altitude = 44330.0 * (1.0 - Math.pow(p / SEA_LEVEL_HPA, 1.0 / 5.255))
                val elapsed = (now - start) / 1000.0
                println("${"%.1f".format(elapsed)}s  ${"%.2f".format(p)} hPa  ${"%.1f".format(t)} C  ${"%.1f".format(altitude)} m")
                rows++
                next += 500
            }
            Thread.sleep(50)
        }
        println("Sampled $rows rows over 30 s")
    }
}
