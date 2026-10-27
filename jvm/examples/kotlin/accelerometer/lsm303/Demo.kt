///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Full
import java.lang.Math.toDegrees
import kotlin.math.atan2
import kotlin.math.sqrt

private val COMPASS_POINTS = arrayOf(
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

fun main() {
    val bus = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    I2CConnection(bus, 0x19).use { accelConn ->
        I2CConnection(bus, 0x1E).use { magConn ->
            val compass = LSM303Full(accelConn, magConn)         // Create LSM303 Full driver, (accel_connection, mag_connection)

            // --- Configure the e-compass: ±2 g at 50 Hz, ±130 µT at 15 Hz ---
            // High-resolution mode keeps 1 mg/LSB tilt resolution; the 50 Hz
            // accelerometer rate gives the tap / free-fall detectors a 20 ms
            // time base (1 LSb = 1/ODR).
            compass.setDataRate(50)                              // Set accelerometer data rate, (rateHz) → Hz
            compass.setMagRange(130)                             // Set magnetometer range, (rangeUt) → µT

            // --- Arm double tap on Z and a latched free-fall generator ---
            // The double tap needs a short time limit and a wide window;
            // free-fall is latched so a brief drop is never missed between polls.
            compass.setClick(0, 0x04, 0.8, 20.0, 40.0, 200.0)   // Configure click detection, (single=0, double=0x04, thresholdG, timeLimitMs, latencyMs, windowMs) → g, ms
            compass.setFreeFall(1, 0.35, 60.0)                  // Configure free-fall, (generator=1, thresholdG, durationMs) → g, ms
            compass.setLatch(1, true)                            // Set interrupt latching, (generator, enabled) → None
            compass.enableInterrupt(LSM303Full.SOURCE_CLICK, 1) // Route interrupt source, (source, pin) → None
            compass.enableInterrupt(LSM303Full.SOURCE_IA1, 1)   // Route interrupt source, (source, pin) → None

            // --- Stream-mode FIFO with a 16-sample watermark for clean averaging ---
            compass.setFifo(2, 16, false)                       // Configure FIFO, (mode, watermark, triggerInt2) → None

            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 60_000) {
                val samples = compass.readFifo(-1)               // Drain the FIFO, (count=-1 all unread) → [g, g, g]*
                var ax = 0.0
                var ay = 0.0
                var az = 0.0
                if (samples.isNotEmpty()) {
                    for (s in samples) {                         // average the drained sample sets
                        ax += s[0]; ay += s[1]; az += s[2]
                    }
                    val n = samples.size.toDouble()
                    ax /= n; ay /= n; az /= n
                }
                val m = compass.readMagnetic()                   // Read 3-axis magnetic field, () → [µT, µT, µT]
                val hdg = compass.heading(true)                  // Compute compass heading, (tiltCompensate=true) → float °
                val field = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
                val name = COMPASS_POINTS[((hdg + 11.25) / 22.5).toInt() % 16]
                val temp = compass.readTemperatureDelta()        // Read relative temperature, () → float °C
                if (samples.isNotEmpty()) {
                    val pitch = toDegrees(atan2(-ax, sqrt(ay * ay + az * az)))
                    val roll = toDegrees(atan2(ay, az))
                    println("%5.1f deg %s  pitch=%+.1f roll=%+.1f  |m|=%.0f uT  dT=%+.1f C".format(
                        hdg, name, pitch, roll, field, temp))
                } else {
                    println("%5.1f deg %s  |m|=%.0f uT  dT=%+.1f C".format(hdg, name, field, temp))
                }

                val status = compass.pollInterrupt()             // Poll all enabled interrupt sources, () → int
                if (status and LSM303Full.SOURCE_CLICK != 0) {
                    println("DOUBLE TAP")
                }
                if (status and LSM303Full.SOURCE_IA1 != 0) {
                    println("FREE FALL")
                }

                Thread.sleep(1000)
            }
        }
    }
}
