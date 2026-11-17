///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-kotlin:1.5.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ltc4151Full

fun main() {
    I2CConnection(1, 0x6A).use { connection ->              // open I²C bus 1, device 0x6A, (bus, address) → I2CConnection
        val ltc = Ltc4151Full(connection, 0.02)             // construct driver, (connection, rSense=0.02 Ω) → Ltc4151Full

        // --- High-side supply monitor ---
        // Continuous scan: print every channel once per second for 20 s.
        repeat(20) {
            val r = ltc.readAll()                           // consistent read of all channels, () → Ltc4151Full.Readings
            println("%.2f V  %.2f mV  %.4f A  %.3f W  adin %.3f V".format(
                r.vin, r.senseV * 1000, r.current, r.power, r.adin))
            Thread.sleep(1000)
        }

        // --- Compare snapshot conversion times ---
        // SENSE needs about 67 ms, VIN and ADIN about 33 ms.
        for ((ch, name) in listOf("SENSE", "VIN", "ADIN").withIndex()) {
            val t0 = System.nanoTime()
            val value = ltc.snapshot(ch)                    // one snapshot conversion, (channel 0–2) → Double V
            println("%s snapshot %.4f V in %d ms".format(name, value, (System.nanoTime() - t0) / 1_000_000))
        }
        println("snapshot mode afterwards ${ltc.isSnapshotMode()}")  // snapshot mode active, () → Boolean

        // --- Toggle the stuck-bus timer and page read/write ---
        // The CONTROL register shows both bits cleared, then restored.
        ltc.setStuckBusTimer(false)                         // stuck-bus reset timer, (enabled) → Unit
        ltc.setPageRw(false)                                // I²C page read/write, (enabled) → Unit
        println("CONTROL (timer off, page off) 0x%02X".format(ltc.readControl()))  // read CONTROL, () → Int
        ltc.setStuckBusTimer(true)
        ltc.setPageRw(true)
        println("CONTROL (restored) 0x%02X".format(ltc.readControl()))

        // --- NTC temperature from ADIN ---
        // A made-up ADIN / VIN code pair run through the datasheet example formula.
        println("temperature %.1f C".format(Ltc4151Full.temperatureFromAdin(900, 1200)))  // NTC example circuit, (adinCode, vinCode) → Double °C

        // --- Power tally ---
        // Sample the power 25 times at 200 ms (5 s) and print min, max and average.
        var pmin = Double.MAX_VALUE
        var pmax = 0.0
        var psum = 0.0
        repeat(25) {
            val p = ltc.power()                             // read load power, () → Double W
            pmin = minOf(pmin, p)
            pmax = maxOf(pmax, p)
            psum += p
            Thread.sleep(200)
        }
        println("power min %.3f W, max %.3f W, avg %.3f W".format(pmin, pmax, psum / 25))
    }
}
