///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-java:1.2.0

// Touchless presence gate with multi-rate ranging: a first measurement picks
// the profile (long range in a dark room, default otherwise), then timed
// continuous ranging at 100 ms feeds an out-of-window interrupt — closer than
// 10 cm is an ENTER event, the scene clearing beyond 80 cm a LEAVE event.
// After 20 events or 60 s, it prints statistics over 10 fresh samples, stops
// ranging and recalibrates. Without a GPIO1 pin on the connection the
// driver's polling thread delivers the events.

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.tof.VL53L0XFull;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Demo {
    static final int MAX_EVENTS = 20;
    static final long MAX_MS = 60_000;

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, VL53L0XFull.DEFAULT_ADDRESS)) {      // open I²C bus, device address, (bus, address=0x29) → I2CConnection
            var sensor = new VL53L0XFull(connection);                                     // Create VL53L0X Full driver, (connection) → VL53L0XFull

            // --- Pick a profile from the ambient light level ---
            // The long-range profile (0.1 MCPS limit, 18/14 PCLK VCSEL periods)
            // reaches ~2 m, but only without IR background; in daylight it mostly
            // adds invalid readings. One single-shot measurement tells us how
            // bright the scene is.
            sensor.distance();                                                            // Measure distance, () → int mm
            var first = sensor.readMeasurement();                                         // Read result block, () → Measurement
            if (first.ambientRateMcps() < 0.5) {
                sensor.setProfile(VL53L0XFull.Profile.LONG_RANGE);                        // Apply ranging profile, (profile) → void
                System.out.printf("dark scene (%.2f MCPS ambient): long range profile%n", first.ambientRateMcps());
            } else {
                sensor.setProfile(VL53L0XFull.Profile.DEFAULT);                           // Apply ranging profile, (profile) → void
                System.out.printf("bright scene (%.2f MCPS ambient): default profile%n", first.ambientRateMcps());
            }

            // --- Arm the presence gate ---
            // Timed ranging every 100 ms keeps the laser mostly idle. The firmware
            // compares each result with the 100 mm / 800 mm window itself and only
            // raises GPIO1 when a reading falls outside it.
            sensor.setInterruptThresholds(100, 800);                                      // Set distance thresholds, (lowMm mm, highMm mm) → void
            sensor.enableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW);                     // Select interrupt source, (source) → void
            sensor.startContinuous(100);                                                  // Start continuous ranging, (periodMs=0 ms) → void

            // --- Classify each event ---
            // The status is already cleared; the result block still holds the
            // measurement that triggered it.
            var events = new ArrayBlockingQueue<Integer>(MAX_EVENTS);
            sensor.onInterrupt(status -> {                                                // Subscribe to GPIO1, (callback) → void
                try {
                    events.offer(sensor.readMeasurement().distanceMm());                  // Read result block, () → Measurement
                } catch (java.io.IOException ignored) {
                    // bus error; skip this event
                }
            });
            long deadline = System.currentTimeMillis() + MAX_MS;
            for (int n = 0; n < MAX_EVENTS; n++) {
                Integer d = events.poll(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (d == null) break;
                System.out.println((d < 100 ? "ENTER " : "LEAVE ") + d + " mm");
            }

            // --- Statistics over fresh samples ---
            // Threshold sources hide ordinary samples from dataReady(), so switch
            // back to new-sample-ready before using the blocking continuous reads.
            sensor.offInterrupt();                                                        // Unsubscribe, () → void
            sensor.enableInterrupt(VL53L0XFull.SOURCE_NEW_SAMPLE_READY);                  // Select interrupt source, (source) → void
            sensor.pollInterrupt();                                                       // Read and clear status, () → int
            int sum = 0, lo = Integer.MAX_VALUE, hi = 0;
            double rate = 0;
            for (int i = 0; i < 10; i++) {
                int d = sensor.readContinuous();                                          // Read next continuous result, () → int mm
                rate += sensor.readMeasurement().signalRateMcps();                        // Read result block, () → Measurement
                sum += d;
                lo = Math.min(lo, d);
                hi = Math.max(hi, d);
            }
            System.out.printf("mean %d mm, min %d mm, max %d mm, signal %.2f MCPS%n", sum / 10, lo, hi, rate / 10);

            // --- Shut down and recalibrate ---
            // Reference calibration must run in software standby. Repeat it
            // whenever the sensor's temperature has drifted more than 8 °C.
            sensor.stopContinuous();                                                      // Stop continuous ranging, () → void
            Thread.sleep(200);
            sensor.recalibrate();                                                         // Rerun reference calibration, () → void
            System.out.println("done");
        }
    }
}
