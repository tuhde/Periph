///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Lps28dfwFull

fun main() {
    val connection = I2CConnection(1, 0x5C)                            // open I²C bus 1, device 0x5C, (bus, address=0x5C) → I2CConnection
    connection.use {
        // --- High-resolution depth/altitude logger: Mode 1, 64-sample average, 25 Hz ---
        // 64× averaging achieves ~1.1 Pa rms noise; Mode 1 keeps full 0.244 Pa resolution.
        val sensor = Lps28dfwFull(it)                                  // construct driver, (connection) → Lps28dfwFull
        sensor.configure(Lps28dfwFull.ODR_25_HZ, Lps28dfwFull.AVG_64, Lps28dfwFull.FS_MODE_1, true, Lps28dfwFull.LFPF_ODR_OVER_4)  // configure chip, (odr=25 Hz, avg=64, fs_mode=1, lpfEn=true, lpfCfg=ODR/4) → void

        // --- Sample every 500 ms for 30 s; report pressure, temperature, altitude ---
        // Sea-level reference uses the ISA standard (1013.25 hPa).
        var samples = 0
        for (n in 0 until 60) {
            val vals = sensor.read()                                    // read both values, () → DoubleArray (hPa, °C)
            val p = vals[0]
            val t = vals[1]
            val alt = 44330.0 * (1.0 - Math.pow(p / 1013.25, 1.0 / 5.255))
            val elapsed = (n + 1) * 0.5
            println("%.1fs  %.2f hPa  %.2f C  %.1f m".format(elapsed, p, t, alt))
            samples++
            Thread.sleep(500)
        }
        println("Total samples: $samples")
    }
}