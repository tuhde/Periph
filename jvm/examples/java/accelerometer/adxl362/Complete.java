///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.accelerometer.Adxl362Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));

        try (var connection = new SPIConnection(bus, dev, 0, 8_000_000)) {        // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
            var chip = new Adxl362Full(connection);                                  // Create ADXL362 Full driver, (connection) → ADXL362Full

            int[] ids = chip.deviceId();                                            // Read device IDs, () → int[4] (DEVID_AD, DEVID_MST, PARTID, REVID)
            System.out.printf("DEVID_AD=0x%02X DEVID_MST=0x%02X PARTID=0x%02X REVID=0x%02X%n",
                    ids[0], ids[1], ids[2], ids[3]);

            chip.setRange(4);                                                       // Set measurement range, (rangeG=4) → void
            chip.setOdr(200.0f);                                                    // Set output data rate, (odrHz=200.0) → void
            chip.setHalfBandwidth(true);                                            // Set antialiasing bandwidth, (enabled=true) → void
            chip.setNoiseMode(Adxl362Full.NOISE_LOW);                               // Set noise mode, (mode=NOISE_LOW=1) → void

            float[] xyz12 = chip.read();                                            // Read 12-bit acceleration, () → float[3] g
            System.out.printf("12-bit: x=%+.3f  y=%+.3f  z=%+.3f%n", xyz12[0], xyz12[1], xyz12[2]);

            float[] xyz8 = chip.read8bit();                                         // Read 8-bit acceleration, () → float[3] g
            System.out.printf(" 8-bit: x=%+.3f  y=%+.3f  z=%+.3f%n", xyz8[0], xyz8[1], xyz8[2]);

            float t = chip.temperature();                                           // Read temperature, () → float °C
            System.out.printf("temperature: %.2f C%n", t);

            int rawStatus = chip.status();                                          // Read STATUS register, () → byte
            System.out.printf("status: 0x%02X%n", rawStatus);
            System.out.printf("awake: %d%n", chip.awake() ? 1 : 0);                 // Check AWAKE bit, () → bool
            System.out.printf("data_ready: %d%n", chip.dataReady() ? 1 : 0);       // Check DATA_READY, () → bool
            System.out.printf("fifo_entries: %d%n", chip.fifoEntries());           // Read FIFO entry count, () → int

            chip.configureFifo(Adxl362Full.FIFO_STREAM, false, 128);               // Configure FIFO, (mode=STREAM=2, storeTemp=false, watermark=128) → void
            chip.setActivityThreshold(0.5f, true);                                  // Set activity threshold, (thresholdG=0.5, referenced=true) → void
            chip.setActivityTime(5);                                                // Set activity time, (samples=5) → void
            chip.setInactivityThreshold(0.2f, true);                                // Set inactivity threshold, (thresholdG=0.2, referenced=true) → void
            chip.setInactivityTime(30);                                             // Set inactivity time, (samples=30) → void
            chip.enableActivityDetection(true);                                     // Enable activity detection, (enabled=true) → void
            chip.enableInactivityDetection(true);                                   // Enable inactivity detection, (enabled=true) → void
            chip.setLinkLoopMode(Adxl362Full.LINKLOOP_LOOP);                       // Set link/loop mode, (mode=LOOP=3) → void

            chip.setInterrupt(1, Adxl362Full.SOURCE_DATA_READY, true);             // Map DATA_READY to INT1, (pin=1, source=DATA_READY=0, enabled=true) → void
            chip.setInterrupt(2, Adxl362Full.SOURCE_AWAKE, true);                  // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → void
            chip.setInterruptPolarity(1, true);                                    // Set INT1 active-low, (pin=1, activeLow=true) → void

            chip.selfTest(true);                                                    // Enable self-test, (enabled=true) → void
            Thread.sleep(500);
            chip.selfTest(false);                                                   // Disable self-test, (enabled=false) → void

            chip.softReset();                                                       // Soft-reset the chip, () → void

            System.out.println("===DONE: 1 passed, 0 failed===");
        }
    }
}