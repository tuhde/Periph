///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.FXOS8700Full;

/**
 * Tilt-compensated electronic compass with motion wake-up.
 *
 * <p>Hybrid mode at 100 Hz per sensor, ±4 g, magnetometer OSR 7. A self-test
 * runs once; the hardware hard-iron calibration tracks the extremes for 15 s
 * while the board is rotated, then the tilt-compensated heading is printed
 * every 250 ms. Orientation changes and motion are reported from the interrupt
 * flags and the chip falls asleep after 10 s of inactivity. Runs for 60 s.
 */
public class Demo {
    static final String[] DIRECTIONS = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                                        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
    static final String[] ORIENTATIONS = {"PORTRAIT UP", "PORTRAIT DOWN", "LANDSCAPE RIGHT", "LANDSCAPE LEFT"};
    static final double DECLINATION = 3.0;   // magnetic declination of the site in degrees

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        try (var conn = new I2CConnection(bus, FXOS8700Full.I2C_ADDRESS)) {
            var chip = new FXOS8700Full(conn);                       // Create FXOS8700 Full driver, (connection)

            // --- Configure hybrid mode ---
            // ±4 g keeps tilt maths precise while tolerating handling; magnetometer
            // oversampling ratio 7 gives the lowest noise at this data rate.
            chip.setRange(4);                                        // Set accelerometer range, (range_g 2|4|8) → void
            chip.setMagOversampling(7, -1);                          // Set magnetometer oversampling, (ratio 0..7, sleep_ratio -1..7) → void

            // --- Check the accelerometer ---
            // The electrostatic self-test must move every axis by the datasheet minimum.
            int[] st = chip.selfTest();                              // Run accelerometer self-test, () → [ok, dx, dy, dz]
            System.out.printf("self-test %s %d %d %d%n", st[0] == 1 ? "OK" : "FAILED", st[1], st[2], st[3]);

            // --- Calibrate the hard-iron offsets ---
            // Auto-calibration writes the midpoint of the tracked min/max to the
            // offset registers every cycle; rotate the board through all
            // orientations meanwhile.
            chip.setMagAutoCalibration(true);                        // Hardware hard-iron calibration, (enabled) → void
            System.out.println("Rotate the board in all directions for 15 s ...");
            for (int s = 0; s < 15; s++) {
                Thread.sleep(1000);
                double[] mm = chip.readMagMinMax();                  // Read tracked min / max, () → [minx..minz, maxx..maxz] µT
                System.out.printf("span %.1f %.1f %.1f uT%n", mm[3] - mm[0], mm[4] - mm[1], mm[5] - mm[2]);
            }
            chip.setMagAutoCalibration(false);                       // Hardware hard-iron calibration, (enabled) → void
            System.out.println("offsets " + java.util.Arrays.toString(chip.getMagOffset()));  // Get hard-iron offsets, () → [x, y, z] µT

            // --- Arm orientation and motion detection ---
            // Orientation changes and motion are routed to INT1; Auto-SLEEP drops the
            // chip to a low rate after 10 s without motion, and motion wakes it again.
            chip.setOrientation(true, 100.0, 45, 4, 4, 2);           // Configure orientation detection, (enabled, debounce_ms, threshold_deg, hysteresis, z_lock, back_front) → void
            chip.setFreefallMotion(true, 7, 0.3, 60.0, true, false); // Configure freefall / motion, (motion, axes, threshold_g, debounce_ms, latch, debounce_clear) → void
            chip.setAutoSleep(true, 12.5, 10.0, FXOS8700Full.SOURCE_FF_MT);  // Configure Auto-SLEEP, (enabled, sleep_rate_hz, timeout_s, wake_sources) → void
            chip.enableInterrupt(FXOS8700Full.SOURCE_ORIENTATION | FXOS8700Full.SOURCE_FF_MT, 1);  // Enable and route interrupt, (source mask, pin 1|2) → void

            // --- Print the heading ---
            // Every 250 ms the tilt-compensated heading is shown with the board's
            // pitch and roll; the temperature is printed once per second.
            for (int tick = 0; tick < 240; tick++) {
                double heading = chip.heading(DECLINATION);          // Compute tilt-compensated heading, (declination_deg) → degrees
                double[] h = chip.readHybrid();                      // Read both sensors, () → [ax, ay, az, mx, my, mz]
                double roll = Math.toDegrees(Math.atan2(h[1], h[2]));
                double pitch = Math.toDegrees(Math.atan2(-h[0], Math.hypot(h[1], h[2])));
                System.out.printf("%5.1f deg %-3s pitch %5.1f roll %5.1f%n", heading,
                        DIRECTIONS[(int) ((heading + 11.25) / 22.5) % 16], pitch, roll);
                int status = chip.pollInterrupt();                   // Poll and clear interrupt sources, () → int
                if ((status & FXOS8700Full.SOURCE_ORIENTATION) != 0) {
                    int[] o = chip.readOrientation();                // Read orientation, () → [changed, lockout, portrait_landscape, back]
                    System.out.println(ORIENTATIONS[o[2]]);
                }
                if ((status & FXOS8700Full.SOURCE_FF_MT) != 0) {
                    System.out.println("MOTION, system mode " + chip.readSystemMode());  // Read SYSMOD, () → int 0 standby | 1 wake | 2 sleep
                }
                if (tick % 4 == 0) {
                    System.out.printf("temperature %.1f C%n", chip.readTemperature());  // Read die temperature, () → °C
                }
                Thread.sleep(250);
            }
        }
    }
}
