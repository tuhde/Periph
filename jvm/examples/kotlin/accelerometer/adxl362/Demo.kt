///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.accelerometer.Adxl362Full
import it.uhde.periph.chips.accelerometer.Adxl362Full.Companion.LINKLOOP_LOOP
import it.uhde.periph.chips.accelerometer.Adxl362Full.Companion.SOURCE_AWAKE

fun main() {
    val bus = System.getenv("SPI_BUS")?.toIntOrNull() ?: 0
    val dev = System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0

    SPIConnection(bus, dev, 0, 8_000_000).use { connection ->                    // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
        val chip = Adxl362Full(connection)                                      // Create ADXL362 Full driver, (connection) → Adxl362Full

        // --- Configure referenced activity/inactivity thresholds ---
        chip.setActivityThreshold(0.25, true)                                   // Set activity threshold, (thresholdG=0.25, referenced=true) → Unit
        chip.setInactivityThreshold(0.15, true)                                 // Set inactivity threshold, (thresholdG=0.15, referenced=true) → Unit
        chip.setInactivityTime(30)                                              // Set inactivity time, (samples=30) → Unit

        // --- Engage linked/loop mode and enable both detectors ---
        chip.enableActivityDetection(true)                                      // Enable activity detection, (enabled=true) → Unit
        chip.enableInactivityDetection(true)                                    // Enable inactivity detection, (enabled=true) → Unit
        chip.setLinkLoopMode(LINKLOOP_LOOP)                                    // Set link/loop mode, (mode=LOOP) → Unit

        // --- Map AWAKE to INT2 and enter wake-up mode ---
        chip.setInterrupt(2, SOURCE_AWAKE, true)                               // Map AWAKE to INT2, (pin=2, source=AWAKE, enabled=true) → Unit
        chip.setWakeupMode(true)                                               // Enter wake-up mode, (enabled=true) → Unit

        // --- Poll AWAKE for 60 s and count asleep<->awake transitions ---
        println("Watching for motion. Pick up or tap the board to wake; "
                + "let it settle to sleep.")
        var lastAwake: Boolean? = null
        var transitions = 0
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 60_000L) {                   // Loop until 60 s elapsed, () → Boolean
            val nowAwake = chip.awake()                                          // Read AWAKE bit, () → Boolean
            if (lastAwake == null || nowAwake != lastAwake) {
                val secs = (System.currentTimeMillis() - start) / 1000.0
                println("%6.2fs  %s".format(secs, if (nowAwake) "AWAKE" else "asleep"))
                transitions++
                lastAwake = nowAwake
            }
            Thread.sleep(200)                                                   // Sleep 200 ms between polls, () → Unit
        }

        println("Total transitions observed: $transitions")
        println("Note: during 'asleep' periods the ADXL362 draws ~270 nA — "
                + "roughly two orders of magnitude below the ~1.8 µA of the "
                + "continuous 100 Hz measurement mode used by the Minimal "
                + "read() example.")
    }
}