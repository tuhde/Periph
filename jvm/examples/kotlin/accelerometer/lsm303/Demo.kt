///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Full
import kotlin.math.atan2
import kotlin.math.sqrt

private val COMPASS = arrayOf(
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
)

private fun compassName(deg: Double): String = COMPASS[Math.round(deg / 22.5).toInt() % 16]

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, 0x19).use { accelConn ->
        I2CConnection(bus, 0x1E).use { magConn ->

            // --- Configure: ±2 g, 50 Hz, high-resolution; magnetometer ±130 µT, 15 Hz ---
            // Tilt compensation needs a stable accelerometer, so the rate is kept moderate.
            val sensor = LSM303Full(accelConn, magConn)            // Create LSM303 driver, (accelConnection, magConnection) → LSM303Full
            sensor.setRange(2)                                      // Set full-scale range, (rangeG=2) → Unit g
            sensor.setDataRate(50)                                  // Set accelerometer ODR, (rateHz=50) → Unit Hz
            sensor.setMode(2)                                       // Set accelerometer mode, (mode=2 high-resolution) → Unit
            sensor.setMagRange(130)                                 // Set magnetometer range, (rangeUt=130) → Unit µT
            sensor.setMagDataRate(15.0)                             // Set magnetometer ODR, (rateHz=15) → Unit Hz

            // --- Arm tap and free-fall detection, routed to INT1 ---
            // A double tap on Z, and a free-fall condition latched until read.
            sensor.setClick(0, 0x04, 0.8, 20.0, 40.0, 200.0)        // Configure click detection, (single, double, threshold_g, time_limit_ms, latency_ms, window_ms) → Unit
            sensor.setFreeFall(1, 0.35, 60.0)                       // Configure free-fall, (generator, threshold_g, duration_ms) → Unit
            sensor.setLatch(1, true)                                // Latch generator 1, (generator, enabled) → Unit
            sensor.enableInterrupt(LSM303Full.SOURCE_CLICK, 1)      // Route click to INT1, (source, pin=1) → Unit
            sensor.enableInterrupt(LSM303Full.SOURCE_IA1, 1)        // Route generator 1 to INT1, (source, pin=1) → Unit

            // --- FIFO stream mode, 16-sample watermark ---
            // The FIFO keeps the most recent samples between the one-second drains.
            sensor.setFifo(2, 16, false)                            // Configure FIFO, (mode=2 stream, watermark=16, triggerInt2=false) → Unit

            val end = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < end) {
                // --- Check interrupt sources ---
                // Reading the latched sources clears them, so each event prints once.
                val pending = sensor.pollInterrupt()                // Poll interrupt sources, () → Int
                if ((pending and LSM303Full.SOURCE_CLICK) != 0) {
                    println("DOUBLE TAP")
                }
                if ((pending and LSM303Full.SOURCE_IA1) != 0) {
                    println("FREE FALL")
                }

                // --- Drain the FIFO and average the samples ---
                // Averaging the window smooths the 50 Hz noise before the tilt calculation.
                val samples = sensor.readFifo()                     // Read FIFO samples, () → Array<DoubleArray> g
                var ax = 0.0
                var ay = 0.0
                var az = 0.0
                for (s in samples) {
                    ax += s[0]
                    ay += s[1]
                    az += s[2]
                }
                if (samples.isNotEmpty()) {
                    ax /= samples.size
                    ay /= samples.size
                    az /= samples.size
                }

                // --- Read the magnetic field and the tilt-compensated heading ---
                // The heading uses both sensors, so the averaged gravity vector is not reused here.
                val m = sensor.readMagnetic()                       // Read magnetic field, () → DoubleArray µT
                val heading = sensor.heading(true)                  // Compass heading (tilt-compensated), (tiltCompensate=true) → Double degrees
                val fieldMag = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])

                // --- Pitch and roll from the averaged acceleration ---
                // Same convention as heading(): roll = atan2(ay, az), pitch from the normalised vector.
                val roll = Math.toDegrees(atan2(ay, az))
                val pitch = Math.toDegrees(atan2(-ax, sqrt(ay * ay + az * az)))

                // --- Relative temperature ---
                // Delta only; the datasheet gives no absolute reference.
                val dT = sensor.readTemperatureDelta()              // Read temperature delta, () → Double °C

                println("heading %.1f° %s  pitch %.1f°  roll %.1f°  |B| %.1f µT  dT %+.2f °C".format(
                    heading, compassName(heading), pitch, roll, fieldMag, dT))
                Thread.sleep(1000)
            }
        }
    }
}
