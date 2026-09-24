///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.2.0

// Long-range doorway people counter with a split ROI. The sensor hangs overhead in a doorway (up
// to 2.5 m). Two narrow 8x16 ROIs (centre SPADs 167 and 231, the left/right half-array centres used
// by ST's own people-counting code) form two virtual beams. Each zone learns its floor distance,
// then counts as occupied when something is more than 300 mm closer; the order in which the zones
// become occupied tells IN from OUT. Every 30 s a signal/ambient snapshot is printed and strong
// sunlight switches to short distance mode. After 60 s or 50 events the full ROI is restored.

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.tof.VL53L1XFull
import it.uhde.periph.chips.tof.VL53L1XMinimal

val ZONE_CENTRES = intArrayOf(167, 231)   // left, right
const val OCCUPIED_MM = 300
const val MAX_EVENTS = 50
const val MAX_MS = 60_000L
const val SNAPSHOT_MS = 30_000L
const val BRIGHT_MCPS = 5.0

/** Distance in mm, or null for an invalid reading. */
fun measure(s: VL53L1XFull, zone: Int): Int? {
    s.setRoiCenter(ZONE_CENTRES[zone])                                                    // Set ROI centre, (spad) → Unit
    val d = s.distance()                                                                  // Measure distance, () → Int mm
    return if (s.rangeValid()) d else null                                                // Check last measurement, () → Boolean
}

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, VL53L1XMinimal.DEFAULT_ADDRESS).use { connection ->                  // open I²C bus, device address, (bus, address=0x29) → I2CConnection
        val s = VL53L1XFull(connection)                                                   // Create VL53L1X Full driver, (connection) → VL53L1XFull

        // --- Two virtual beams ---
        // Long mode reaches the floor from a 2.5 m ceiling; a 33 ms budget keeps the two zones
        // fast enough to catch a walking person. An 8x16 ROI covers one half of the SPAD array,
        // so alternating the centre alternates beams.
        s.setDistanceMode(VL53L1XFull.DistanceMode.LONG)                                  // Set distance mode, (mode) → Unit
        s.setTimingBudget(33000)                                                          // Set timing budget, (budgetUs µs) → Unit
        s.setRoi(8, 16)                                                                   // Set ROI size, (width SPADs, height SPADs) → Unit
        println("optical centre SPAD ${s.opticalCenter()}, zone centres 167/231")         // Read optical-centre SPAD, () → Int

        // --- Learn the empty doorway ---
        // Twenty readings per zone give the floor distance each beam sees when nobody is there;
        // invalid readings are ignored.
        val baseline = DoubleArray(2) { zone ->
            val values = (0 until 20).mapNotNull { measure(s, zone) }
            if (values.isEmpty()) 4000.0 else values.average()
        }
        println("baseline left %.0f mm, right %.0f mm".format(baseline[0], baseline[1]))

        // --- Count crossings ---
        // A person entering blocks the left beam first, then the right one (and the reverse when
        // leaving). Once both beams clear, the recorded order decides the direction.
        var countIn = 0
        var countOut = 0
        var events = 0
        val sequence = mutableListOf<Int>()
        val start = System.currentTimeMillis()
        var lastSnapshot = start
        while (events < MAX_EVENTS && System.currentTimeMillis() - start < MAX_MS) {
            val r = listOf(measure(s, 0), measure(s, 1))
            val occupied = r.mapIndexed { zone, v -> v != null && v < baseline[zone] - OCCUPIED_MM }
            for (zone in 0..1) if (occupied[zone] && zone !in sequence) sequence += zone
            if (!occupied[0] && !occupied[1] && sequence.isNotEmpty()) {
                when (sequence) {
                    listOf(0, 1) -> countIn++
                    listOf(1, 0) -> countOut++
                }
                events++
                println("IN $countIn OUT $countOut (left ${r[0]}, right ${r[1]})")
                sequence.clear()
            }

            // --- Watch the light ---
            // Sunlight through an open door raises the ambient rate and eats long-mode range;
            // short mode keeps working up to ~1.3 m.
            if (System.currentTimeMillis() - lastSnapshot >= SNAPSHOT_MS) {
                lastSnapshot = System.currentTimeMillis()
                s.distance()                                                              // Measure distance, () → Int mm
                val m = s.readMeasurement()                                               // Read result block, () → Measurement
                println("signal %.2f MCPS, ambient %.2f MCPS".format(m.signalRateMcps, m.ambientRateMcps))
                if (m.ambientRateMcps > BRIGHT_MCPS && s.distanceMode() == VL53L1XFull.DistanceMode.LONG) {  // Read distance mode, () → DistanceMode
                    s.setDistanceMode(VL53L1XFull.DistanceMode.SHORT)                     // Set distance mode, (mode) → Unit
                    println("bright ambient light: switched to short distance mode")
                }
            }
        }

        // --- Restore the full field of view ---
        s.setRoi(16, 16)                                                                  // Set ROI size, (width SPADs, height SPADs) → Unit
        println("done: IN $countIn OUT $countOut")
    }
}
