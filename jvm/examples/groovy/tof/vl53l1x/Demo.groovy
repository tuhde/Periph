///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-groovy:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.tof.VL53L1XFull

import java.util.ArrayList
import java.util.List

// Long-range doorway people counter with a split ROI. The sensor hangs overhead in a doorway (up
// to 2.5 m). Two narrow 8x16 ROIs (centre SPADs 167 and 231, the left/right half-array centres used
// by ST's own people-counting code) form two virtual beams. Each zone learns its floor distance,
// then counts as occupied when something is more than 300 mm closer; the order in which the zones
// become occupied tells IN from OUT. Every 30 s a signal/ambient snapshot is printed and strong
// sunlight switches to short distance mode. After 60 s or 50 events the full ROI is restored.
public class Demo {
    static final int[] ZONE_CENTRES = [167, 231] as int[]    // left, right
    static final int OCCUPIED_MM = 300
    static final int MAX_EVENTS = 50
    static final long MAX_MS = 60_000
    static final long SNAPSHOT_MS = 30_000
    static final double BRIGHT_MCPS = 5.0

    /** @return distance in mm, or -1 for an invalid reading */
    static int measure(VL53L1XFull s, int zone) throws Exception {
        s.setRoiCenter(ZONE_CENTRES[zone])                                              // Set ROI centre, (spad) → void
        int d = s.distance()                                                            // Measure distance, () → int mm
        return s.rangeValid() ? d : -1                                                  // Check last measurement, () → boolean
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"))

        try (var connection = new I2CConnection(bus, VL53L1XFull.DEFAULT_ADDRESS)) {   // open I²C bus, device address, (bus, address=0x29) → I2CConnection
            var s = new VL53L1XFull(connection)                                         // Create VL53L1X Full driver, (connection) → VL53L1XFull

            // --- Two virtual beams ---
            // Long mode reaches the floor from a 2.5 m ceiling; a 33 ms budget keeps the two zones
            // fast enough to catch a walking person. An 8x16 ROI covers one half of the SPAD array,
            // so alternating the centre alternates beams.
            s.setDistanceMode(VL53L1XFull.DistanceMode.LONG)                            // Set distance mode, (mode) → void
            s.setTimingBudget(33000)                                                    // Set timing budget, (budgetUs µs) → void
            s.setRoi(8, 16)                                                             // Set ROI size, (width SPADs, height SPADs) → void
            System.out.println("optical centre SPAD " + s.opticalCenter() + ", zone centres 167/231")   // Read optical-centre SPAD, () → int

            // --- Learn the empty doorway ---
            // Twenty readings per zone give the floor distance each beam sees when nobody is there
            // invalid readings are ignored.
            double[] baseline = new double[2]
            for (int zone = 0; zone < 2; zone++) {
                long sum = 0
                int n = 0
                for (int i = 0; i < 20; i++) {
                    int d = measure(s, zone)
                    if (d >= 0) { sum += d; n++; }
                }
                baseline[zone] = n > 0 ? (double) sum / n : 4000
            }
            System.out.printf("baseline left %.0f mm, right %.0f mm%n", baseline[0], baseline[1])

            // --- Count crossings ---
            // A person entering blocks the left beam first, then the right one (and the reverse when
            // leaving). Once both beams clear, the recorded order decides the direction.
            int countIn = 0, countOut = 0, events = 0
            List<Integer> sequence = new ArrayList<>()
            long start = System.currentTimeMillis()
            long lastSnapshot = start
            while (events < MAX_EVENTS && System.currentTimeMillis() - start < MAX_MS) {
                int[] r = [measure(s, 0), measure(s, 1)] as int[]
                boolean[] occupied = new boolean[2]
                for (int zone = 0; zone < 2; zone++) {
                    occupied[zone] = r[zone] >= 0 && r[zone] < baseline[zone] - OCCUPIED_MM
                    if (occupied[zone] && !sequence.contains(zone)) sequence.add(zone)
                }
                if (!occupied[0] && !occupied[1] && !sequence.isEmpty()) {
                    if (sequence.equals(List.of(0, 1))) countIn++
                    else if (sequence.equals(List.of(1, 0))) countOut++
                    events++
                    System.out.printf("IN %d OUT %d (left %d, right %d)%n", countIn, countOut, r[0], r[1])
                    sequence.clear()
                }

                // --- Watch the light ---
                // Sunlight through an open door raises the ambient rate and eats long-mode range
                // short mode keeps working up to ~1.3 m.
                if (System.currentTimeMillis() - lastSnapshot >= SNAPSHOT_MS) {
                    lastSnapshot = System.currentTimeMillis()
                    s.distance()                                                        // Measure distance, () → int mm
                    var m = s.readMeasurement()                                         // Read result block, () → Measurement
                    System.out.printf("signal %.2f MCPS, ambient %.2f MCPS%n", m.signalRateMcps(), m.ambientRateMcps())
                    if (m.ambientRateMcps() > BRIGHT_MCPS
                            && s.distanceMode() == VL53L1XFull.DistanceMode.LONG) {     // Read distance mode, () → DistanceMode
                        s.setDistanceMode(VL53L1XFull.DistanceMode.SHORT)               // Set distance mode, (mode) → void
                        System.out.println("bright ambient light: switched to short distance mode")
                    }
                }
            }

            // --- Restore the full field of view ---
            s.setRoi(16, 16)                                                            // Set ROI size, (width SPADs, height SPADs) → void
            System.out.printf("done: IN %d OUT %d%n", countIn, countOut)
        }
    }
}
