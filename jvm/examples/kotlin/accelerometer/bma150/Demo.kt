///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.Bma150Full
import kotlin.math.sqrt

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.parseInt(it.replaceFirst("^0[xX]".toRegex(), ""), 16) } ?: 0x38
    I2CConnection(bus, addr).use { connection ->
        val accel = Bma150Full(connection)        // Create BMA150 driver, (connection)

        // --- Configure ±8 g / 190 Hz and arm LG + HG latched interrupts ---
        // ±8 g gives 64 LSB/g, plenty of headroom for shock detection. 190 Hz
        // bandwidth is wide enough to capture a 2 ms high-g spike without
        // aliasing. Latched interrupts free the polling loop from having to
        // catch a transient.
        accel.setRange(8)                         // Set measurement range, (rangeG=2) → g
        accel.setBandwidth(190)                   // Set bandwidth, (bandwidthHz=25) → Hz
        accel.setLatch(true)                      // Set latched interrupts, (enabled=false) → None
        accel.setLowG(0.4, 40)                    // Configure low-g, (thresholdG, durationMs, hysteresisG=0, counter=0) → g, ms
        accel.setHighG(4.0, 2)                    // Configure high-g, (thresholdG, durationMs, hysteresisG=0, counter=0) → g, ms

        val start = System.currentTimeMillis()
        var lastHeartbeat = 0L
        var lastPoll = 0L

        // --- 60-second free-fall / shock logger ---
        // User is expected to drop or shake the board at some point during
        // the 60 s window. Between events the magnitude sits at ≈1.00 g
        // (gravity). Each latched interrupt is reported with a timestamp,
        // the latest (x, y, z), temperature, and a free-fall or shock tag.
        while (System.currentTimeMillis() - start < 60_000) {
            val now = System.currentTimeMillis()
            if (now - lastHeartbeat >= 1000) {
                val xyz = accel.read()               // Read 3-axis acceleration, () → [g, g, g]
                val mag = sqrt(xyz[0]*xyz[0] + xyz[1]*xyz[1] + xyz[2]*xyz[2])
                val temp = accel.readTemperature()    // Read temperature, () → °C
                println("%5d  x=%+.3f  y=%+.3f  z=%+.3f  |a|=%.3f g  T=%.1f C".format(
                    (now - start) / 1000, xyz[0], xyz[1], xyz[2], mag, temp))
                lastHeartbeat = now
            }
            if (now - lastPoll >= 50) {
                val status = accel.pollInterrupt()    // Read STATUS, () → int
                if ((status and 0x08) != 0) {          // STATUS_LG_LATCHED (bit 3)
                    val xyz = accel.read()              // Read 3-axis acceleration, () → [g, g, g]
                    val temp = accel.readTemperature() // Read temperature, () → °C
                    println("%5d  FREE FALL detected  x=%+.3f  y=%+.3f  z=%+.3f  T=%.1f C".format(
                        (now - start) / 1000, xyz[0], xyz[1], xyz[2], temp))
                    accel.clearInterrupt()              // Clear latched interrupts, () → None
                }
                if ((status and 0x04) != 0) {          // STATUS_HG_LATCHED (bit 2)
                    val xyz = accel.read()              // Read 3-axis acceleration, () → [g, g, g]
                    val temp = accel.readTemperature() // Read temperature, () → °C
                    println("%5d  SHOCK detected     x=%+.3f  y=%+.3f  z=%+.3f  T=%.1f C".format(
                        (now - start) / 1000, xyz[0], xyz[1], xyz[2], temp))
                    accel.clearInterrupt()              // Clear latched interrupts, () → None
                }
                lastPoll = now
            }
            Thread.sleep(10)
        }
        println("done")
    }
}
