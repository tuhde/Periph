///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.accelerometer.Adxl362Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));

        try (var connection = new SPIConnection(bus, dev, 0, 8_000_000)) {        // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
            var chip = new Adxl362Full(connection);                                  // Create ADXL362 Full driver, (connection) → ADXL362Full

            // --- Configure referenced activity/inactivity thresholds ---
            chip.setActivityThreshold(0.25f, true);                                  // Set activity threshold, (thresholdG=0.25, referenced=true) → void
            chip.setInactivityThreshold(0.15f, true);                                // Set inactivity threshold, (thresholdG=0.15, referenced=true) → void
            chip.setInactivityTime(30);                                             // Set inactivity time, (samples=30) → void

            // --- Engage linked/loop mode and enable both detectors ---
            chip.enableActivityDetection(true);                                     // Enable activity detection, (enabled=true) → void
            chip.enableInactivityDetection(true);                                   // Enable inactivity detection, (enabled=true) → void
            chip.setLinkLoopMode(Adxl362Full.LINKLOOP_LOOP);                       // Set link/loop mode, (mode=LOOP=3) → void

            // --- Map AWAKE to INT2 and enter wake-up mode ---
            chip.setInterrupt(2, Adxl362Full.SOURCE_AWAKE, true);                  // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → void
            chip.setWakeupMode(true);                                               // Enter wake-up mode, (enabled=true) → void

            // --- Poll AWAKE for 60 s and count asleep<->awake transitions ---
            System.out.println("Watching for motion. Pick up or tap the board to wake; "
                    + "let it settle to sleep.");
            Boolean lastAwake = null;
            int transitions = 0;
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 60_000) {                   // Loop until 60 s elapsed, () → bool
                boolean nowAwake = chip.awake();                                    // Read AWAKE bit, () → bool
                if (lastAwake == null || nowAwake != lastAwake) {
                    double secs = (System.currentTimeMillis() - start) / 1000.0;
                    System.out.printf("%6.2fs  %s%n", secs, nowAwake ? "AWAKE" : "asleep");
                    transitions++;
                    lastAwake = nowAwake;
                }
                Thread.sleep(200);                                                   // Sleep 200 ms between polls, () → None
            }

            System.out.printf("Total transitions observed: %d%n", transitions);
            System.out.println("Note: during 'asleep' periods the ADXL362 draws ~270 nA — "
                    + "roughly two orders of magnitude below the ~1.8 µA of the "
                    + "continuous 100 Hz measurement mode used by the Minimal "
                    + "read() example.");
        }
    }
}