///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

// Touchless presence gate with multi-rate ranging: a first measurement picks
// the profile (long range in a dark room, default otherwise), then timed
// continuous ranging at 100 ms feeds an out-of-window interrupt — closer than
// 10 cm is an ENTER event, the scene clearing beyond 80 cm a LEAVE event.
// After 20 events or 60 s, it prints statistics over 10 fresh samples, stops
// ranging and recalibrates. Without a GPIO1 pin on the connection the
// driver's polling thread delivers the events.

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.tof.VL53L0XFull
import it.uhde.periph.chips.tof.VL53L0XMinimal
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

const val MAX_EVENTS = 20
const val MAX_MS = 60_000L

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, VL53L0XMinimal.DEFAULT_ADDRESS).use { connection ->                // open I²C bus, device address, (bus, address=0x29) → I2CConnection
        val sensor = VL53L0XFull(connection)                                              // Create VL53L0X Full driver, (connection) → VL53L0XFull

        // --- Pick a profile from the ambient light level ---
        // The long-range profile (0.1 MCPS limit, 18/14 PCLK VCSEL periods)
        // reaches ~2 m, but only without IR background; in daylight it mostly
        // adds invalid readings. One single-shot measurement tells us how
        // bright the scene is.
        sensor.distance()                                                                 // Measure distance, () → Int mm
        val first = sensor.readMeasurement()                                              // Read result block, () → Measurement
        if (first.ambientRateMcps < 0.5) {
            sensor.setProfile(VL53L0XFull.Profile.LONG_RANGE)                             // Apply ranging profile, (profile) → Unit
            println("dark scene (%.2f MCPS ambient): long range profile".format(first.ambientRateMcps))
        } else {
            sensor.setProfile(VL53L0XFull.Profile.DEFAULT)                                // Apply ranging profile, (profile) → Unit
            println("bright scene (%.2f MCPS ambient): default profile".format(first.ambientRateMcps))
        }

        // --- Arm the presence gate ---
        // Timed ranging every 100 ms keeps the laser mostly idle. The firmware
        // compares each result with the 100 mm / 800 mm window itself and only
        // raises GPIO1 when a reading falls outside it.
        sensor.setInterruptThresholds(100, 800)                                           // Set distance thresholds, (lowMm mm, highMm mm) → Unit
        sensor.enableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW)                          // Select interrupt source, (source) → Unit
        sensor.startContinuous(100)                                                       // Start continuous ranging, (periodMs=0 ms) → Unit

        // --- Classify each event ---
        // The status is already cleared; the result block still holds the
        // measurement that triggered it.
        val events = ArrayBlockingQueue<Int>(MAX_EVENTS)
        sensor.onInterrupt({ _ ->                                                         // Subscribe to GPIO1, (callback, intPin=connection.intPin()) → Unit
            runCatching { events.offer(sensor.readMeasurement().distanceMm) }             // Read result block, () → Measurement
        })
        val deadline = System.currentTimeMillis() + MAX_MS
        for (n in 0 until MAX_EVENTS) {
            val d = events.poll(maxOf(1L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS) ?: break
            println("${if (d < 100) "ENTER" else "LEAVE"} $d mm")
        }

        // --- Statistics over fresh samples ---
        // Threshold sources hide ordinary samples from dataReady(), so switch
        // back to new-sample-ready before using the blocking continuous reads.
        sensor.offInterrupt()                                                             // Unsubscribe, () → Unit
        sensor.enableInterrupt(VL53L0XFull.SOURCE_NEW_SAMPLE_READY)                       // Select interrupt source, (source) → Unit
        sensor.pollInterrupt()                                                            // Read and clear status, () → Int
        val samples = mutableListOf<Int>()
        var rate = 0.0
        repeat(10) {
            samples += sensor.readContinuous()                                            // Read next continuous result, () → Int mm
            rate += sensor.readMeasurement().signalRateMcps                               // Read result block, () → Measurement
        }
        println("mean %.0f mm, min %d mm, max %d mm, signal %.2f MCPS".format(
            samples.average(), samples.min(), samples.max(), rate / samples.size))

        // --- Shut down and recalibrate ---
        // Reference calibration must run in software standby. Repeat it
        // whenever the sensor's temperature has drifted more than 8 °C.
        sensor.stopContinuous()                                                           // Stop continuous ranging, () → Unit
        Thread.sleep(200)
        sensor.recalibrate()                                                              // Rerun reference calibration, () → Unit
        println("done")
    }
}
